package lol.http

import java.net.{ InetSocketAddress }

import io.netty.channel.{ ChannelInitializer }
import io.netty.channel.nio.{ NioEventLoopGroup }
import io.netty.bootstrap.{ ServerBootstrap }
import io.netty.channel.socket.nio.{ NioServerSocketChannel }
import io.netty.util.concurrent.{ GenericFutureListener, Future => NettyFuture }
import io.netty.channel.socket.{ SocketChannel }
import io.netty.handler.logging.{ LogLevel, LoggingHandler }
import io.netty.handler.ssl.{ JdkSslContext, ClientAuth }
import io.netty.handler.codec.http.{
  DefaultHttpResponse,
  HttpRequest,
  HttpVersion => NettyHttpVersion,
  HttpResponseStatus }
import scala.util.{ Try }
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Promise, Future }
import scala.concurrent.duration._

import fs2.{ Stream, Task, Strategy, async }

import internal.NettySupport._

/** Allow to configure an HTTP server.
  * @param ioThreads the number of threads used for the IO work. Default to `max(availableProcessors, 2)`.
  * @param tcpNoDelay if true disable Nagle's algorithm. Default `true`.
  * @param bufferSize if defined used as a hint for the TCP buffer size. If none use the system default. Default to `None`.
  * @param debug if defined log the TCP traffic with the provided logger name. Default to `None`.
  */
case class ServerOptions(
  ioThreads: Int = Math.max(Runtime.getRuntime.availableProcessors, 2),
  tcpNoDelay: Boolean = true,
  bufferSize: Option[Int] = None,
  debug: Option[String] = None
)

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
  def ssl: Option[SSL.Configuration]

  /** @return the server options such as the number of IO thread used. */
  def options: ServerOptions

  /** @return the TCP port from the underlying server socket. */
  def port = socketAddress.getPort

  /** Stop this server instance gracefully. It ensures that no requests are handled for
    * 'the quiet period' (usually a couple seconds) before it shuts itself down. If a request is
    * submitted during the quiet period, it is guaranteed to be accepted and the quiet period will
    * start over.
    * @param quietPeriod The quiet period for the graceful shutdown.
    * @param timeout The maximum amount of time to wait until the server is shutdown regardless of the quiet period.
    * @return a Future resolved as soon as the server is shutdown.
    */
  def stop(quietPeriod: Duration = 2 seconds, timeout: Duration = 15 seconds): Future[Unit]

  override def toString() = s"Server(socketAddress=$socketAddress, ssl=$ssl, options=$options)"
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
  * function will be run on the provided [[scala.concurrent.ExecutionContext ExecutionContext]].
  * This function should be non-blocking, but you can also decide to go with a blocking service
  * if you provide an appropriate ExecutionContext (just note that if the ExecutionContext is fully
  * blocked, the HTTP server is fully blocked).
  *
  * The service function will be called by the server as soon as new HTTP request header has been received.
  * The server also set up a lazy stream for the body. User code can pull from this stream and consume it
  * if needed. Otherwise it will be drained after exchange completion (ie. when the response has been fully sent).
  *
  * The response value returned by the servcie function will be transfered back to the client.
  */
object Server {
  private val defaultErrorResponse = Response(500, Content.of("Internal server error"))
  private val defaultErrorHandler = (e: Throwable) => {
    e.printStackTrace()
    defaultErrorResponse
  }

  /**
   * Start a new HTTP server.
   * @param port the TCP port used. If set to 0, it starts on any available port. This is the default.
   * @param address the ip address this server listen at. __0.0.0.0__ means listening on all available address. This is the default.
   * @param ssl if provided, the SSL configuration to use. In this case the server will listen for HTTPS requests.
   * @param options the server options such as the number of IO thread to use.
   * @param executor the [[scala.concurrent.ExecutionContext ExecutionContext]] to use to run user code.
   * @return a server instance that you can stop later.
   */
  def listen(
    port: Int = 0,
    address: String = "0.0.0.0",
    ssl: Option[SSL.Configuration] = None,
    options: ServerOptions = ServerOptions(),
    onError: (Throwable => Response) = defaultErrorHandler
  )(service: Service)(implicit executor: ExecutionContext): Server = {
    implicit val S = Strategy.fromExecutionContext(executor)

    def serveRequest(request: Request) = {
      Future(try { service(request) } catch { case e: Throwable => Future.failed(e) })(executor).
        flatMap(identity).
        recoverWith { case e: Throwable => Try(onError(e)).toOption.getOrElse(defaultErrorResponse) }
    }

    val eventLoop = new NioEventLoopGroup(options.ioThreads)
    val bootstrap = new ServerBootstrap().
      group(eventLoop).
      channel(classOf[NioServerSocketChannel]).
      childHandler(new ChannelInitializer[SocketChannel] {
        override def initChannel(channel: SocketChannel) = {
          channel.config.setTcpNoDelay(options.tcpNoDelay)
          options.bufferSize.foreach { size =>
            channel.config.setReceiveBufferSize(size)
            channel.config.setSendBufferSize(size)
          }
          ssl.foreach { ssl =>
            val sslCtx = new JdkSslContext(ssl.ctx, false, ClientAuth.NONE)
            channel.pipeline.addLast("SSL", sslCtx.newHandler(channel.alloc()))
          }
          val connection = Netty.serverConnection(channel, options.debug)

          // Loop over incoming request
          def go(): Task[Unit] = for {
            // Read the next request
            message <- connection.read()
            (nettyRequest, contentStream) = (message._1.asInstanceOf[HttpRequest], message._2)
            // Track the number of content readers
            readers <- async.semaphore[Task](1)
            // Apply user code and retrieve the response
            response <- Task.fromFuture(serveRequest(Request(
              method = HttpMethod(nettyRequest.method.name),
              url = nettyRequest.uri,
              scheme = if(ssl.isDefined) "https" else "http",
              headers = nettyRequest.headers.asScala.map { h =>
                (HttpString(h.getKey), HttpString(h.getValue))
              }.toMap,
              content = Content(
                stream =
                  Stream.
                    // The content stream can be read only once
                    eval(readers.tryDecrement).flatMap {
                      case false =>
                        Stream.fail(Error.StreamAlreadyConsumed)
                      case true =>
                        contentStream
                    }
                  // XXX: Content headers
                )
            )))
            // If the user code did not open the content stream
            // we need to drain it now.
            _ <- readers.tryDecrement.flatMap {
              case false =>
                Task.now(())
              case true =>
                contentStream.drain.run
            }
            // Write the response message (will block until the content
            // stream is fully read)
            _ <- {
              val nettyResponse = new DefaultHttpResponse(
                NettyHttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(response.status)
              )
              (response.content.headers ++ response.headers).foreach { case (key,value) =>
                nettyResponse.headers.set(key.toString, value.toString)
              }
              // Write the HTTP response
              connection.write(nettyResponse, response.content.stream)
            }
            _ <- Task.suspend(
              // Switch protocol if needed
              if(response.status == 101) {
                connection.upgrade().flatMap { upstream =>
                  val downstream = response.upgradeConnection(upstream)
                  (downstream to connection.writeBytes).drain.run
                }
              }
              // Next!
              else {
                go()
              }
            )
          } yield ()
          go().unsafeRunAsync { case _ => channel.close() }
        }
      })
    options.debug.foreach { logger =>
      bootstrap.handler(new LoggingHandler(s"$logger",LogLevel.INFO))
    }

    try {
      val channel = bootstrap.bind(address, port).sync().channel()
      val localAddress = Try(channel.localAddress.asInstanceOf[InetSocketAddress]).getOrElse(Panic.!!!())
      val (options0, ssl0) = (options, ssl)
      new Server {
        val socketAddress = localAddress
        val ssl = ssl0
        val options = options0
        def stop(quietPeriod: Duration, timeout: Duration) = {
          val p = Promise[Unit]
          eventLoop.shutdownGracefully(quietPeriod.toMillis, timeout.toMillis, java.util.concurrent.TimeUnit.MILLISECONDS).
            asInstanceOf[NettyFuture[Unit]].
            addListener(new GenericFutureListener[NettyFuture[Unit]] {
              override def operationComplete(f: NettyFuture[Unit]) = {
                if(f.isSuccess) {
                  p.success(())
                }
                else {
                  p.failure(f.cause)
                }
              }
            })
          p.future
        }
        def apply(request: Request) = serveRequest(request)
      }
    }
    catch {
      case e: Throwable =>
        eventLoop.shutdownGracefully()
        throw e
    }
  }

}
