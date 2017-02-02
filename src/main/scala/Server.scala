package lol.http

import java.net.{ InetSocketAddress }

import scala.util.{ Try, Failure }
import scala.collection.JavaConverters._
import scala.concurrent.{ ExecutionContext, Future }

import io.undertow.{ Undertow }
import io.undertow.UndertowOptions._
import io.undertow.server.{ HttpServerExchange, HttpHandler, HttpUpgradeListener }
import io.undertow.util.{ Headers => H, HttpString => HString }

import fs2.{ Task, Strategy }

import org.xnio.Options._
import org.xnio.{ StreamConnection }

case class ServerOptions(
  ioThreads: Int = Math.max(Runtime.getRuntime.availableProcessors, 2),
  bufferSize: Int = 16 * 1024,
  directBuffers: Boolean = true,
  tcpNoDelay: Boolean = true
)

class Server(
  private val server: Undertow,
  private val ssl: Option[SSL.Configuration],
  private val options: ServerOptions
) {
  private def listenerInfo = {
    server.getListenerInfo.asScala.headOption.getOrElse(sys.error("Should not happen"))
  }
  def socketAddress = {
    Try(listenerInfo.getAddress.asInstanceOf[InetSocketAddress]).getOrElse(sys.error("Should not happen"))
  }
  def port = socketAddress.getPort
  def stop(): Unit = server.stop()
  override def toString() = s"Server($listenerInfo, $options)"
}

object Server {
  java.util.logging.Logger.getLogger("org.xnio").setLevel(java.util.logging.Level.OFF)

  private val defaultErrorResponse = Response(500, Content("Internal server error"))

  def listen(
    port: Int = 0,
    address: String = "0.0.0.0",
    ssl: Option[SSL.Configuration] = None,
    options: ServerOptions = ServerOptions(),
    onError: (Throwable => Response) = _ => defaultErrorResponse
  )(f: Service)(implicit executor: ExecutionContext) = {

    implicit val S = Strategy.fromExecutionContext(executor)

    val server = {
      val server = Undertow.builder()
        .setIoThreads(options.ioThreads)
        .setDirectBuffers(options.directBuffers)
        .setBufferSize(options.bufferSize)
        .setServerOption(ENABLE_HTTP2.asInstanceOf[org.xnio.Option[Any]], true)
        .setServerOption(TCP_NODELAY.asInstanceOf[org.xnio.Option[Any]], options.tcpNoDelay)
        .setServerOption(WORKER_TASK_CORE_THREADS.asInstanceOf[org.xnio.Option[Any]], 0)
        .setSocketOption(TCP_NODELAY.asInstanceOf[org.xnio.Option[Any]], options.tcpNoDelay)
      ssl.map(ssl => server.addHttpsListener(port, address, ssl.ctx)).getOrElse(server.addHttpListener(port, address))
    }.setHandler(new HttpHandler() {
        override def handleRequest(exchange: HttpServerExchange): Unit = {

          val readContent = for {
            in <- Task.delay(exchange.getRequestChannel)
            requestStream <- Internal.source(in, exchange.getConnection.getByteBufferPool)
          } yield {
            in.resumeReads()
            Request(
              method = HttpMethod(exchange.getRequestMethod.toString),
              path = exchange.getRequestPath,
              queryString = Some(exchange.getQueryString).filterNot(_.isEmpty),
              scheme = exchange.getRequestScheme,
              content = Content(
                requestStream,
                Option(exchange.getRequestContentLength).filter(_ >= 0),
                Option(exchange.getRequestHeaders.getFirst(H.CONTENT_TYPE)).map { contentType =>
                  Map(Headers.ContentType -> HttpString(contentType))
                }.getOrElse(Map.empty)
              ),
              headers = exchange.getRequestHeaders.getHeaderNames.asScala.map { h =>
                (HttpString(h.toString), HttpString(exchange.getRequestHeaders.getFirst(h)))
              }.toMap
            )
          }

          readContent.unsafeRunAsyncFuture.flatMap { request =>
            (try { f(request) } catch { case e: Throwable => Future.failed(e) })
              .recoverWith { case e: Throwable => Try(onError(e)).toOption.getOrElse(defaultErrorResponse) }
              .flatMap {

                // Protocol upgrade
                case Response(101, _, headers, upgradedConnection) =>
                  Future.fromTry(Try {
                    headers.foreach { case (key,value) =>
                      exchange.getResponseHeaders.put(new HString(key.toString), value.toString)
                    }
                    exchange.upgradeChannel(new HttpUpgradeListener {
                      def handleUpgrade(socket: StreamConnection, exchange: HttpServerExchange): Unit = {
                        (for {
                          in <- Task.delay(socket.getSourceChannel)
                          stream <- Internal.source(in, exchange.getConnection.getByteBufferPool)
                        } yield stream).
                        flatMap { source =>
                          (for {
                            sink <- Task.delay(upgradedConnection(source))
                            out <- Internal.sink(socket.getSinkChannel)
                            flush <- sink.to(out).drain.run
                          } yield flush)
                        }.
                        unsafeRunAsyncFuture.
                        andThen { case _ => socket.close() }
                      }
                    })
                  })

                // Normal response
                case Response(status, content, headers, _) =>
                  (for {
                    _ <- Task.delay {
                      exchange.setStatusCode(status)
                      content.length.foreach(exchange.setResponseContentLength)
                      content.headers.foreach { case (key,value) =>
                        exchange.getResponseHeaders.put(new HString(key.toString), value.toString)
                      }
                      headers.foreach { case (key,value) =>
                        exchange.getResponseHeaders.put(new HString(key.toString), value.toString)
                      }
                    }
                    out <- Internal.sink(exchange.getResponseChannel)
                    flush <- content.stream.to(out).drain.run
                  } yield flush).
                  unsafeRunAsyncFuture
              }
          }
          .andThen { case _ => exchange.endExchange() }
          .andThen { case Failure(e) => throw e }
        }
      }).build()

    server.start()
    new Server(server, ssl, options)
  }

}
