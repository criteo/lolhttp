package lol.http

import java.net.InetSocketAddress

import org.http4s.blaze.http._
import org.http4s.blaze.channel._
import org.http4s.blaze.http.HttpServerStageConfig
import org.http4s.blaze.channel.nio1.NIO1SocketServerGroup
import org.http4s.blaze.http.http1.server.Http1ServerStage
import org.http4s.blaze.pipeline.{LeafBuilder, TrunkBuilder}
import org.http4s.blaze.pipeline.stages.SSLStage
import org.http4s.blaze.pipeline.stages.monitors.BasicConnectionMonitor
import org.http4s.blaze.http.http2.server.ServerSelector

import scala.util.Try
import scala.concurrent.{ExecutionContext, Future }

import cats.implicits._

import cats.effect.IO
import cats.effect.concurrent.Semaphore

import fs2._

import java.nio.ByteBuffer

/** An HTTP server.
  *
  * {{{
  * val eventuallyContent = server(Get("/hello")).flatMap { response =>
  *   response.readAs[String]
  * }
  * }}}
  *
  * An HTTP server is a [[lol.http.Service service]] function. It handles HTTP requests
  * and eventually returns HTTP responses.
  *
  * The server listen for HTTP connections on the [[socketAddress]] TCP socket. If [[ssl]] is defined,
  * it supports TLS connections. Calling the [[stop]] operation asks for a graceful shutdown.
  */
trait Server extends Service {
  /** @return the underlying server socket. */
  def socketAddress: InetSocketAddress

  /** @return the SSL configuration if enabled. */
  def ssl: Option[SSL.ServerConfiguration]

  /** @return the server protocol. */
  def protocol: String

  /** @return the TCP port from the underlying server socket. */
  def port = socketAddress.getPort

  /** Stop this server instance. */
  def stop(): Unit

  override def toString() = s"Server(socketAddress=$socketAddress, ssl=$ssl, protocol=$protocol)"
}

/** Build and start HTTP servers.
  *
  * {{{
  * Server.listen(8888) { request =>
  *   Ok("Hello world!")
  * }
  * }}}
  *
  * Starting an HTTP server require a [[lol.http.Service service]] function. The service
  * function will be run on the provided `scala.concurrent.ExecutionContext`.
  * This function should be non-blocking, but you can also decide to go with a blocking service
  * if you provide an appropriate ExecutionContext (just note that if the ExecutionContext is fully
  * blocked, the HTTP server is fully blocked).
  *
  * The service function will be called by the server as soon as new HTTP request header has been received.
  * The server also set up a lazy stream for the body. User code can pull from this stream and consume it
  * if needed. Otherwise it will be drained after exchange completion (ie. when the response has been fully sent).
  *
  * The response value returned by the service function will be transfered back to the client.
  */
object Server {
  val defaultErrorResponse = Response(500, Content.of("Internal server error"))
  val defaultErrorHandler = (e: Throwable) => {
    e.printStackTrace()
    defaultErrorResponse
  }

  /**
   * Start a new HTTP server.
   * @param port the TCP port used. If set to 0, it starts on any available port. This is the default.
   * @param address the ip address this server listen at. __0.0.0.0__ means listening on all available address. This is the default.
   * @param ssl if provided, the SSL configuration to use. In this case the server will listen for HTTPS requests.
   * @param options the server options such as the number of IO thread to use.
   * @param executor the `scala.concurrent.ExecutionContext ` to use to run user code.
   * @return a server instance that you can stop later.
   */
  def listen(
    port: Int = 0,
    address: String = "0.0.0.0",
    ssl: Option[SSL.ServerConfiguration] = None,
    protocol: String = HTTP,
    onError: (Throwable => Response) = defaultErrorHandler
  )(service: Service)(implicit executor: ExecutionContext): Server = {
    implicit val cs = IO.contextShift(executor)

    val ssl0 = ssl
    val protocol0 = protocol
    val port0 = port
    val emptyBuffer = ByteBuffer.allocate(0)

    def serveRequest(request: Request): IO[Response] =
      IO.suspend(service(request)).attempt.map {
        case Left(e) => Try(onError(e)).toOption.getOrElse(defaultErrorResponse)
        case Right(x) => x
      }

    def serveRequest0(connection: SocketConnection)(request: HttpRequest): Future[RouteAction] =
      (for {
        // We use synchronouse queue for inbound & outbound trafic, so
        // backpressure is fully managed by user code.
        inboundQueue <- fs2.concurrent.Queue.synchronous[IO, ByteBuffer]
        outboundQueue <- fs2.concurrent.Queue.synchronous[IO, ByteBuffer]
        // Read asynchronously the request body (if any) in the inbound queue
        requestBytes = request.body
        _ <-
          Stream.eval(
            for {
              chunk <-
                IO.fromFuture(IO(requestBytes())).recoverWith {
                  case e: Throwable =>
                    inboundQueue.enqueue1(emptyBuffer) >> IO.raiseError(e)
                }
              hasRemaining = chunk.hasRemaining
              _ <- inboundQueue.enqueue1(chunk)
            } yield hasRemaining
          ).takeWhile(identity).repeat.compile.drain.runAsync(_ => IO.unit).toIO
        // As soon as we get the request header, invoke user code
        readers <- Semaphore[IO](1)
        response <-
          serveRequest(Request(
            HttpMethod(request.method),
            request.url,
            if(ssl.isDefined) "https" else "http",
            Content(
              Stream.
                // The content stream can be read only once
                eval(readers.tryAcquire).flatMap {
                  case false =>
                    Stream.raiseError[IO](Error.StreamAlreadyConsumed)
                  case true =>
                    inboundQueue.dequeue
                      .takeWhile(_.hasRemaining)
                      .map(buf => Chunk.byteBuffer(buf))
                      .flatMap(bytes => Stream.chunk(bytes))
                      .onFinalize(IO(requestBytes.discard()))
                },
              request.headers.collect {
                case (key, value) if key.toLowerCase.startsWith("content-") =>
                  (HttpString(key), HttpString(value))
              }.toMap
            ),
            request.headers.map {
              case (key, value) =>
                (HttpString(key), HttpString(value))
            }.toMap,
            if(request.majorVersion == 2) HTTP2 else HTTP,
            connection.remote match {
              case socketAddress: InetSocketAddress =>
                Some(socketAddress.getAddress)
              case _ =>
                None
            }
          ))
        // Copy the response body to the client
        endOfStream <- fs2.concurrent.SignallingRef[IO, Boolean](false)
        _ <-
          (
            response.content.stream
              .handleErrorWith {
                case e: Throwable =>
                  Stream.eval(outboundQueue.enqueue1(emptyBuffer) >> IO.raiseError(e))
              }
              .interruptWhen(endOfStream)
              .chunks.evalMap[IO, Unit] {
                case chunk =>
                  outboundQueue.enqueue1(ByteBuffer.wrap(chunk.toArray))
              } ++ Stream.eval(outboundQueue.enqueue1(emptyBuffer))
          ).compile.drain.runAsync(_ => IO.unit).toIO
        // As soon as we get the response headers, write the response back
        routeAction = new RouteAction {
          override def handle[T <: BodyWriter](responder: (HttpResponsePrelude) => T) = {
            val writer = responder(
              HttpResponsePrelude(
                response.status,
                response.status.toString,
                (response.content.headers ++ response.headers).map {
                  case (key, value) =>
                    (key.toString, value.toString)
                }.toSeq
              )
            )
            outboundQueue.dequeue.evalMap {
              case chunk if !chunk.hasRemaining =>
                (endOfStream.set(true) >> IO.fromFuture(IO(writer.close(None)))).map(Right.apply _)
              case chunk =>
                IO.fromFuture(IO(writer.write(chunk) >> writer.flush())).recoverWith {
                  case e: Throwable =>
                    endOfStream.set(true) >> IO.raiseError(e)
                }.map(Left.apply _)

            }
            .takeThrough {
              case Left(_) => true
              case Right(_) => false
            }
            .compile.last.map {
              case Some(Right(finished)) =>
                finished
              case x =>
                Panic.!!!("Unreacheable code")
            }.unsafeToFuture
          }
        }
      } yield routeAction).unsafeToFuture

    new Server {
      val ssl = ssl0
      val protocol = protocol0
      val config = HttpServerStageConfig()
      val f: SocketPipelineBuilder =
        new BasicConnectionMonitor().wrapBuilder { connection =>
          if(protocol0 == HTTP2) {
            val sslEngine = ssl.getOrElse(SSL.selfSigned).engine
            Future.successful(
              TrunkBuilder(new SSLStage(sslEngine))
                .cap(ServerSelector(sslEngine, serveRequest0(connection), config))
            )
          }
          else {
            val builder = LeafBuilder(new Http1ServerStage(serveRequest0(connection), config))
            Future.successful {
              ssl0 match {
                case Some(ssl) =>
                  builder.prepend(new SSLStage(ssl.engine, 100 * 1024))
                case None =>
                  builder
              }
            }
          }
        }
      val ch =
        NIO1SocketServerGroup.fixedGroup(workerThreads = 4).bind(new InetSocketAddress(address, port0), f)
          .getOrElse(sys.error(s"Failed to start server on port $port0"))
      val socketAddress = ch.socketAddress
      def stop() = ch.close()
      def apply(req: Request) = serveRequest(req)

      // Keep the server alive until `stop()` is called.
      new Thread { override def run = ch.join() }.start()
    }
  }

}
