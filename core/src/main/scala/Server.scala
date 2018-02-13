package lol.http

import java.net.{ InetSocketAddress }

import io.netty.channel.{
  ChannelHandlerContext,
  ChannelInitializer }
import io.netty.channel.nio.{ NioEventLoopGroup }
import io.netty.bootstrap.{ ServerBootstrap }
import io.netty.channel.socket.nio.{ NioServerSocketChannel }
import io.netty.util.concurrent.{
  GenericFutureListener,
  Future => NettyFuture }
import io.netty.channel.socket.{ SocketChannel }
import io.netty.handler.logging.{
  LogLevel,
  LoggingHandler }
import io.netty.buffer.{
  ByteBuf,
  ByteBufUtil,
  Unpooled
}
import io.netty.handler.codec.{ ByteToMessageDecoder }
import io.netty.handler.ssl.{
  ApplicationProtocolNames,
  ApplicationProtocolConfig,
  ApplicationProtocolNegotiationHandler }
import io.netty.handler.codec.http2.{ Http2CodecUtil }

import fs2.{ Stream }

import scala.util.{ Try }
import scala.concurrent.{
  ExecutionContext,
  Promise,
  Future }
import scala.concurrent.duration._

import cats.effect.{ IO }

import internal.NettySupport._

/** Allow to configure an HTTP server.
  * @param ioThreads the number of threads used for the IO work. Default to `max(availableProcessors, 2)`.
  * @param tcpNoDelay if true disable Nagle's algorithm. Default `true`.
  * @param bufferSize if defined used as a hint for the TCP buffer size. If none use the system default. Default to `None`.
  * @param protocols the allowed protocols (HTTP, HTTP2 or both). If SSL enabled the protocol is negociated using ALPN.
  *                  Without SSL enabled only direct HTTP2 connections with prior knowledge are supported.
  * @param debug if defined log the TCP traffic with the provided logger name. Default to `None`.
  */
case class ServerOptions(
  ioThreads: Int = Math.max(Runtime.getRuntime.availableProcessors, 2),
  tcpNoDelay: Boolean = true,
  bufferSize: Option[Int] = None,
  protocols: Set[String] = Set(HTTP),
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
  def ssl: Option[SSL.ServerConfiguration]

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
  def stop(quietPeriod: Duration = 2.seconds, timeout: Duration = 15.seconds): Future[Unit]

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
    ssl: Option[SSL.ServerConfiguration] = None,
    options: ServerOptions = ServerOptions(),
    onError: (Throwable => Response) = defaultErrorHandler
  )(service: Service)(implicit executor: ExecutionContext): Server = {

    def serveRequest(request: Request) =
      IO.suspend(service(request)).attempt.map {
        case Left(e) => Try(onError(e)).toOption.getOrElse(defaultErrorResponse)
        case Right(x) => x
      }

    // Setup an HTTP connection on the channel, and loop over incoming requests
    def newConnection(channel: SocketChannel, protocol: String): Unit = {
      val connection = Netty.serverConnection(channel, options.debug, protocol)
      val asyncResult: PartialFunction[Either[Throwable,Unit],Unit] = {
        case Right(_) =>
        case Left(e) =>
          if(channel.isOpen) channel.close()
      }

      // Loop over incoming request
      Stream.eval(connection()).evalMap { case (request, responseHandler) =>
        // Handle the request in a totally asynchronous way,
        // allowing the loop to pick the next available request ASAP
        // if the underlying protocol does allow it (HTTP/1.1 pipelining
        // or HTTP/2.0)
        IO((for {
          // Apply user code and retrieve the response
          response <- serveRequest(request)
          // If the user code did not open the content stream
          // we need to drain it now
          _ <- request.content.stream.compile.drain.attempt.flatMap {
            case Left(e) => e match {
              case Error.StreamAlreadyConsumed => IO.unit
              case _ => IO.raiseError(e)
            }
            case Right(_) => IO.unit
          }
          // Write the response message
          _ <- responseHandler(response)
        } yield ()).unsafeRunAsync(asyncResult))
      }.repeat.compile.drain.unsafeRunAsync(asyncResult)
    }

    val eventLoop = new NioEventLoopGroup(options.ioThreads)
    val bootstrap = new ServerBootstrap().
      group(eventLoop).
      channel(classOf[NioServerSocketChannel]).
      childHandler(new ChannelInitializer[SocketChannel] {
        val HTTP2_PREFACE = Unpooled.unreleasableBuffer(Http2CodecUtil.connectionPrefaceBuf)
        override def initChannel(channel: SocketChannel) = {
          channel.config.setTcpNoDelay(options.tcpNoDelay)
          options.bufferSize.foreach { size =>
            channel.config.setReceiveBufferSize(size)
            channel.config.setSendBufferSize(size)
          }
          ssl match {
            // SSL with APLN
            case Some(ssl) if options.protocols.contains(HTTP2) =>
              val ctx = ssl.ctx.builder.
                applicationProtocolConfig(new ApplicationProtocolConfig(
                  ApplicationProtocolConfig.Protocol.ALPN,
                  ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                  ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                  (Seq(ApplicationProtocolNames.HTTP_2) ++
                  (if(options.protocols.contains(HTTP)) Seq(ApplicationProtocolNames.HTTP_1_1) else Nil)): _*)).
                build()
              channel.pipeline.addLast("SSL", ctx.newHandler(channel.alloc()))
              channel.pipeline.addLast("APLN", new ApplicationProtocolNegotiationHandler(
                if(options.protocols.contains(HTTP)) ApplicationProtocolNames.HTTP_1_1 else ApplicationProtocolNames.HTTP_2) {
                def configurePipeline(ctx: ChannelHandlerContext, protocol: String) =
                  newConnection(channel, protocol match {
                    case "h2" => HTTP2
                    case _ => HTTP
                  })
              })
            // SSL, no protocol negotiation
            case Some(ssl) if options.protocols.contains(HTTP) =>
              val ctx = ssl.ctx.builder.build()
              channel.pipeline.addLast("SSL", ctx.newHandler(channel.alloc()))
              newConnection(channel, HTTP)
            // No SSL but peek inbound messages to detect HTTP2 protocol
            case None if options.protocols.contains(HTTP2) =>
              channel.pipeline.addLast("HTTP2", new ByteToMessageDecoder {
                override def decode(ctx: ChannelHandlerContext, in: ByteBuf, out: java.util.List[Object]): Unit = {
                    val prefaceLength = HTTP2_PREFACE.readableBytes
                    val bytesRead = Math.min(in.readableBytes, prefaceLength)
                    if(!ByteBufUtil.equals(
                      HTTP2_PREFACE,
                      HTTP2_PREFACE.readerIndex,
                      in,
                      in.readerIndex(),
                      bytesRead
                    )) {
                      if(options.protocols.contains(HTTP)) {
                        newConnection(channel, HTTP)
                        ctx.pipeline.remove(this)
                      }
                      else {
                        channel.close()
                      }
                    }
                    else if(bytesRead == prefaceLength) {
                      newConnection(channel, HTTP2)
                      ctx.pipeline.remove(this)
                    }
                }
              })
            // Simple HTTP connection
            case None if options.protocols.contains(HTTP) =>
              newConnection(channel, HTTP)
            case _ =>
              channel.close()
          }
        }
      })
    options.debug.foreach { logger =>
      bootstrap.handler(new LoggingHandler(s"$logger", LogLevel.INFO))
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
