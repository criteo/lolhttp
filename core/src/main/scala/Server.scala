package lol.http

import java.net.{ InetSocketAddress }
import java.util.concurrent.atomic.{ AtomicLong }

import io.netty.channel.{
  ChannelInitializer,
  SimpleChannelInboundHandler,
  ChannelHandlerContext }
import io.netty.buffer.{ ByteBuf }
import io.netty.channel.nio.{ NioEventLoopGroup }
import io.netty.bootstrap.{ ServerBootstrap }
import io.netty.channel.socket.nio.{ NioServerSocketChannel }
import io.netty.channel.socket.{ SocketChannel }
import io.netty.handler.logging.{ LogLevel, LoggingHandler }
import io.netty.handler.ssl.{ JdkSslContext, ClientAuth }
import io.netty.handler.codec.http.{
  DefaultHttpResponse,
  HttpRequest,
  HttpContent,
  LastHttpContent,
  HttpVersion => NettyHttpVersion,
  HttpResponseStatus,
  HttpObject,
  HttpRequestDecoder,
  HttpResponseEncoder }

import scala.util.{ Try }
import scala.collection.JavaConverters._
import scala.concurrent.{ ExecutionContext, Future }

import fs2.{ Stream, Task, Strategy, Chunk, async }

import internal.NettySupport._

case class ServerOptions(
  ioThreads: Int = Math.max(Runtime.getRuntime.availableProcessors, 2),
  tcpNoDelay: Boolean = true,
  bufferSize: Option[Int] = None,
  debug: Option[String] = None
)

class Server(
  val socketAddress: InetSocketAddress,
  val ssl: Option[SSL.Configuration],
  val options: ServerOptions,
  private val doClose: () => Unit
) {
  def port = socketAddress.getPort
  def stop() = doClose()
  override def toString() = s"Server($socketAddress, $options)"
}

object Server {
  private val defaultErrorResponse = Response(500, Content.of("Internal server error"))

  def listen(
    port: Int = 0,
    address: String = "0.0.0.0",
    ssl: Option[SSL.Configuration] = None,
    options: ServerOptions = ServerOptions(),
    onError: (Throwable => Response) = _ => defaultErrorResponse
  )(f: Service)(implicit executor: ExecutionContext): Server = {
    implicit val S = Strategy.fromExecutionContext(executor)

    val connectionIds = new AtomicLong(0)
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
        channel.config.setAutoRead(false)

        // used for internal sanity check
        val id = connectionIds.incrementAndGet()

        ssl.foreach { ssl =>
          val sslCtx = new JdkSslContext(ssl.ctx, false, ClientAuth.NONE)
          channel.pipeline.addLast("SSL", sslCtx.newHandler(channel.alloc()))
        }
        options.debug.foreach { logger =>
          channel.pipeline.addLast("Debug", new LoggingHandler(s"$logger.$id",LogLevel.INFO))
        }
        channel.pipeline.addLast("HttpRequestDecoder", new HttpRequestDecoder())
        channel.pipeline.addLast("HttpResponseEncoder", new HttpResponseEncoder())
        channel.pipeline.addLast("CatchAll", new SimpleChannelInboundHandler[Any] {
          override def channelRead0(ctx: ChannelHandlerContext, msg: Any) = {
            Panic.!!!(s"Missed $msg")
          }
          override def exceptionCaught(ctx: ChannelHandlerContext, e: Throwable) = {
            ctx.close()
            e match {
              case Panic(_) => throw e
              case e =>
            }
          }
        })

        var handleContent = Option.empty[(Option[HttpObject] => Unit)]
        channel.pipeline.addBefore(
          "CatchAll",
          "RequestHandler",
          new SimpleChannelInboundHandler[HttpObject] {
            override def channelRead0(ctx: ChannelHandlerContext, msg: HttpObject) = msg match {
              case nettyRequest: HttpRequest =>
                var buffer: Chunk[Byte] = Chunk.empty
                val (content, readers, requestFullyConsumed) = (for {
                  c <- async.synchronousQueue[Task,Chunk[Byte]]
                  r <- async.semaphore[Task](1)
                  e <- async.signalOf[Task,Boolean](false)
                } yield (c,r,e)).unsafeRun()

                handleContent = Some({
                  case Some(part: HttpContent) =>
                    buffer = Chunk.concat(Seq(buffer, part.content.toChunk))
                    if(part.isInstanceOf[LastHttpContent]) {
                      requestFullyConsumed.set(true).unsafeRun()
                    }
                  case None =>
                    (for {
                      _ <- if(buffer.nonEmpty) content.enqueue1(buffer) else Task.now(())
                      _ <- if(requestFullyConsumed.get.unsafeRun()) {
                        Task.fromFuture(channel.runInEventLoop {
                          handleContent = None
                          // Read the next request
                          channel.read()
                        }).flatMap { _ =>
                          content.enqueue1(Chunk.empty)
                        }
                      }
                      else {
                        Task.fromFuture(channel.runInEventLoop {
                          buffer = Chunk.empty
                          channel.read()
                        })
                      }
                    } yield ()).unsafeRunAsyncFuture()
                  case _ =>
                    Panic.!!!()
                })

                val request = Request(
                  method = HttpMethod(nettyRequest.method.name),
                  url = nettyRequest.uri,
                  scheme = "http",
                  headers = nettyRequest.headers.asScala.map { h =>
                    (HttpString(h.getKey), HttpString(h.getValue))
                  }.toMap,
                  content = Content(
                    Stream.
                      // The content stream can be read only once
                      eval(readers.tryDecrement).
                      flatMap {
                        case false =>
                          Stream.fail(Error.StreamAlreadyConsumed)
                        case true =>
                          content.dequeue.
                            takeWhile(_.nonEmpty).
                            flatMap(Stream.chunk).
                            onFinalize {
                              // When user code finalize the stream
                              // we drain the queue so the connection
                              // is ready for the next request
                              content.dequeue.
                                interruptWhen(requestFullyConsumed).
                                run
                            }
                      }
                  )
                )

                Future(try { f(request) } catch { case e: Throwable => Future.failed(e) })(executor).
                  flatMap(identity).
                  recoverWith { case e: Throwable => Try(onError(e)).toOption.getOrElse(defaultErrorResponse) }.
                  flatMap { response =>
                    val nettyResponse = new DefaultHttpResponse(
                      NettyHttpVersion.HTTP_1_1,
                      HttpResponseStatus.valueOf(response.status)
                    )
                    (response.headers ++ response.content.headers).foreach { case (key,value) =>
                      nettyResponse.headers.set(key.toString, value.toString)
                    }

                    channel.runInEventLoop {
                      if(response.status == 101) {
                        // Upgrade the connection
                        var buffer: Chunk[Byte] = Chunk.empty
                        val (content, readers) = (for {
                          c <- async.synchronousQueue[Task,Chunk[Byte]]
                          r <- async.semaphore[Task](1)
                        } yield (c,r)).unsafeRun()

                        channel.pipeline.remove("HttpRequestDecoder")
                        channel.pipeline.remove("RequestHandler")
                        channel.pipeline.addBefore(
                          "CatchAll",
                          "UpgradedConnectionHandler",
                          new SimpleChannelInboundHandler[ByteBuf] {
                            override def channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) = {
                              buffer = Chunk.concat(Seq(buffer, msg.toChunk))
                            }
                            override def channelReadComplete(ctx: ChannelHandlerContext) = {
                              (for {
                                _ <- if(buffer.nonEmpty) content.enqueue1(buffer) else Task.now(())
                                _ <- Task.fromFuture {
                                  channel.runInEventLoop {
                                    buffer = Chunk.empty
                                    channel.read()
                                  }
                                }
                              } yield ()).unsafeRunAsyncFuture()
                            }
                            override def channelInactive(ctx: ChannelHandlerContext) = {
                              content.enqueue1(Chunk.empty).unsafeRunAsyncFuture()
                            }
                          }
                        )
                        channel.read()

                        val downstream = response.upgradeConnection(
                          Stream.
                            // The content stream can be read only once
                            eval(readers.tryDecrement).
                            flatMap {
                              case false =>
                                Stream.fail(Error.StreamAlreadyConsumed)
                              case true =>
                                content.dequeue.
                                  takeWhile(_.nonEmpty).
                                  flatMap(Stream.chunk)
                            }
                        )

                        (for {
                          _ <- channel.writeAndFlush(nettyResponse).toFuture
                          _ <- channel.runInEventLoop { channel.pipeline.remove("HttpResponseEncoder") }
                          _ <- (downstream to channel.bytesSink).
                            onFinalize(Task.fromFuture {
                              channel.runInEventLoop {
                                channel.close()
                              }
                            }).
                            run.
                            unsafeRunAsyncFuture()
                        } yield ())
                      }
                      else {
                        (for {
                          _ <- channel.writeAndFlush(nettyResponse).toFuture
                          _ <- (response.content.stream to channel.httpContentSink).run.unsafeRunAsyncFuture()
                          _ <- request.drain
                        } yield ())
                      }
                    }
                  }

              case msg =>
                handleContent.map(_(Some(msg))).getOrElse { Panic.!!!() }
            }
            override def channelReadComplete(ctx: ChannelHandlerContext) = {
              handleContent.foreach(_(None))
            }
          }
        )

        // Wait for the first request
        channel.read()
      }
    })
    options.debug.foreach { logger =>
      bootstrap.handler(new LoggingHandler(s"$logger",LogLevel.INFO))
    }

    try {
      val channel = bootstrap.bind(address, port).sync().channel()
      val localAddress = Try(channel.localAddress.asInstanceOf[InetSocketAddress]).getOrElse(Panic.!!!())
      new Server(localAddress, ssl, options, () => eventLoop.shutdownGracefully())
    }
    catch {
      case e: Throwable =>
        eventLoop.shutdownGracefully()
        throw e
    }
  }

}
