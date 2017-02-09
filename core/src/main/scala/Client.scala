package lol.http

import scala.util.{ Try, Success, Failure }
import scala.collection.JavaConverters._
import scala.concurrent.{ ExecutionContext, Future, Promise }

import io.netty.channel.{
  ChannelInitializer,
  SimpleChannelInboundHandler,
  ChannelHandlerContext }
import io.netty.buffer.{ ByteBuf }
import io.netty.bootstrap.{ Bootstrap }
import io.netty.channel.nio.{ NioEventLoopGroup }
import io.netty.channel.socket.{ SocketChannel }
import io.netty.channel.socket.nio.{ NioSocketChannel }
import io.netty.handler.logging.{ LogLevel, LoggingHandler }
import io.netty.handler.ssl.{ JdkSslContext, ClientAuth }
import io.netty.handler.codec.http.{
  DefaultHttpRequest,
  HttpResponse,
  HttpContent,
  LastHttpContent,
  HttpVersion => NettyHttpVersion,
  HttpMethod => NettyHttpMethod,
  HttpObject,
  HttpClientCodec,
  HttpContentDecompressor }

import fs2.{ async, Stream, Task, Strategy, Chunk }

import java.util.concurrent.{ ArrayBlockingQueue }
import java.util.concurrent.atomic.{ AtomicLong, AtomicBoolean }

import internal.NettySupport._

case class ClientOptions(
  ioThreads: Int = Math.min(Runtime.getRuntime.availableProcessors, 2),
  tcpNoDelay: Boolean = true,
  bufferSize: Option[Int] = None,
  debug: Option[String] = None
)

private[http] class Connection(
  private val channel: SocketChannel,
  private val debug: Option[String]
)(implicit executor: ExecutionContext) {
  implicit val S = Strategy.fromExecutionContext(executor)

  private var upgraded = false

  // used for internal sanity check
  val id = Connection.connectionIds.incrementAndGet()
  val concurrentUses = new AtomicLong(0)

  debug.foreach { logger =>
    channel.pipeline.addLast("Debug", new LoggingHandler(s"$logger.$id", LogLevel.INFO))
  }
  channel.pipeline.addLast("Http", new HttpClientCodec())
  channel.pipeline.addLast("HttpCompress", new HttpContentDecompressor())
  channel.pipeline.addLast("CatchAll", new SimpleChannelInboundHandler[Any] {
    override def channelRead0(ctx: ChannelHandlerContext, msg: Any) = msg match {
      case last: LastHttpContent if upgraded && last.content.readableBytes == 0 =>
        // Expected because we upgraded the connection
      case msg =>
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

  val closed: Future[Unit] = channel.closeFuture.toFuture.map(_ => ())
  def close(): Future[Unit] = channel.close().toFuture.map(_ => ())
  def isOpen: Boolean = channel.isOpen

  def apply(request: Request): Future[(Response, async.immutable.Signal[Task,Boolean])] = {
    if(concurrentUses.incrementAndGet() != 1) Panic.!!!()

    val (content, readers, releaseConnection) = (for {
      c <- async.synchronousQueue[Task,Chunk[Byte]]
      r <- async.semaphore[Task](1)
      e <- async.signalOf[Task,Boolean](false)
    } yield (c,r,e)).unsafeRun()

    var buffer: Chunk[Byte] = Chunk.empty
    var responseDone = false

    val eventuallyResponse = Promise[Response]
    // if the connection is closed before we received the Http response
    closed.andThen { case _ =>
      eventuallyResponse.tryComplete(Failure(Error.ConnectionClosed))
    }

    val nettyRequest = new DefaultHttpRequest(
      NettyHttpVersion.HTTP_1_1,
      new NettyHttpMethod(request.method.toString),
      s"${request.path}${request.queryString.map(q => s"?$q").getOrElse("")}"
    )
    (request.headers ++ request.content.headers).foreach { case (key,value) =>
      nettyRequest.headers.set(key.toString, value.toString)
    }

    // install a new InboundHandler to handle this response header + content
    channel.pipeline.addBefore(
      "CatchAll",
      "ResponseHandler",
      new SimpleChannelInboundHandler[HttpObject] {
      override def channelRead0(ctx: ChannelHandlerContext, msg: HttpObject) = {
        msg match {
          case nettyResponse: HttpResponse =>
            if(eventuallyResponse.isCompleted) Panic.!!!()
            if(buffer.nonEmpty) Panic.!!!()
            eventuallyResponse.success {
              Response(
                status = nettyResponse.status.code,
                headers = nettyResponse.headers.asScala.map { h =>
                  (HttpString(h.getKey), HttpString(h.getValue))
                }.toMap,
                content = nettyResponse.status.code match {
                  case 101 =>
                    // If the server accepted to upgrade the connection,
                    // we consider the upcoming content as part of the new protocol
                    // so we don't provide any content for the response itself
                    Content.empty
                  case _ =>
                    Content(
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
                                  interruptWhen(releaseConnection).
                                  drain.run
                              }
                        }
                    )
                },
                upgradeConnection = nettyResponse.status.code match {
                  case 101 => (upstream) => {
                    Stream.
                      eval(readers.tryDecrement).
                      flatMap {
                        case false =>
                          Stream.fail(Error.StreamAlreadyConsumed)
                        case true =>
                          (upstream to channel.bytesSink).run.unsafeRunAsyncFuture()
                          content.dequeue.
                            takeWhile(_.nonEmpty).
                            flatMap(Stream.chunk).
                            onFinalize(Task.fromFuture {
                              channel.runInEventLoop {
                                channel.close()
                              }
                            })
                      }
                  }
                  case _ => (upstream) => {
                    Stream.fail(Error.UpgradeRefused)
                  }
                }
              )
            }
            if(nettyResponse.status.code == 101) {
              // This is not an HTTP connection anymore
              channel.pipeline.remove("ResponseHandler")
              channel.pipeline.remove("HttpCompress")
              channel.pipeline.remove("Http")
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
              upgraded = true
            }
          case part: HttpContent =>
            buffer = Chunk.concat(Seq(buffer, part.content.toChunk))
            if(part.isInstanceOf[LastHttpContent]) responseDone = true
          case _ =>
            Panic.!!!()
        }
      }
      override def channelReadComplete(ctx: ChannelHandlerContext) = {
        (for {
          _ <- if(buffer.nonEmpty) content.enqueue1(buffer) else Task.now(())
          _ <- if(responseDone) content.enqueue1(Chunk.empty) else Task.now(())
          _ <- Task.fromFuture {
            channel.runInEventLoop {
              if(responseDone) {
                // This response has been completely handled
                channel.pipeline.remove("ResponseHandler")
                if(concurrentUses.decrementAndGet() != 0) Panic.!!!()
                releaseConnection.set(true).unsafeRun()

                // Close the connection if requested by the protocol
                val response = eventuallyResponse.future.value.flatMap(_.toOption).getOrElse(Panic.!!!())
                if(
                  response.headers.get(Headers.Connection).exists(_ == "Close") ||
                  request.headers.get(Headers.Connection).exists(_ == "Close")
                ) channel.close()
              }
              else {
                buffer = Chunk.empty
                channel.read()
              }
            }
          }
        } yield ()).unsafeRunAsyncFuture()
      }
    })

    (for {
      _ <- channel.writeAndFlush(nettyRequest).toFuture
      _ <- (request.content.stream to channel.httpContentSink).run.unsafeRunAsyncFuture()
      _ <- channel.runInEventLoop { channel.read() } // Read the next response message
      response <- eventuallyResponse.future
    } yield (response, releaseConnection)).
    andThen {
      case Failure(e) =>
        eventuallyResponse.tryComplete(Failure(e))
        channel.close()
    }
  }
}

private object Connection {
  val connectionIds = new AtomicLong(0)
}

class Client(
  val host: String,
  val port: Int,
  val scheme: String,
  val ssl: SSL.Configuration,
  val options: ClientOptions,
  val maxConnections: Int,
  val maxWaiters: Int
)(implicit executor: ExecutionContext) extends Service {

  private val eventLoop = new NioEventLoopGroup(options.ioThreads)
  private val bootstrap = new Bootstrap().
    group(eventLoop).
    channel(classOf[NioSocketChannel]).
    remoteAddress(host, port).
    handler(new ChannelInitializer[SocketChannel] {
    override def initChannel(channel: SocketChannel) = {
      channel.config.setTcpNoDelay(options.tcpNoDelay)
      options.bufferSize.foreach { size =>
        channel.config.setReceiveBufferSize(size)
        channel.config.setSendBufferSize(size)
      }
      channel.config.setAutoRead(false)
      Option(scheme).filter(_ == "https").foreach { _ =>
        val sslCtx = new JdkSslContext(ssl.ctx, true, ClientAuth.NONE)
        channel.pipeline.addLast("SSL", sslCtx.newHandler(channel.alloc()))
      }
    }
  })

  // -- Connection pool

  private val closed = new AtomicBoolean(false)
  private val liveConnections = new AtomicLong(0)
  private val connections = new ArrayBlockingQueue[Connection](maxConnections)
  private val availableConnections = new ArrayBlockingQueue[Connection](maxConnections)
  private val waiters = new ArrayBlockingQueue[Promise[Connection]](maxWaiters)

  def nbConnections: Int = liveConnections.intValue

  private def waitConnection(): Future[Connection] = {
    val p = Promise[Connection]
    if(waiters.offer(p)) p.future else {
      Future.failed(Error.TooManyWaiters)
    }
  }

  private def destroyConnection(c: Connection): Unit = {
    availableConnections.remove(c)
    if(!connections.remove(c)) Panic.!!!()
    liveConnections.decrementAndGet()
  }

  private def releaseConnection(c: Connection): Unit = {
    if(c.concurrentUses.get > 0) Panic.!!!()
    if(c.isOpen) Option(waiters.poll).fold {
      if(!availableConnections.offer(c)) Panic.!!!()
    } { _.success(c) }
  }

  private def acquireConnection(): Future[Connection] = {
    if(closed.get) Future.failed(Error.ClientAlreadyClosed) else
    Option(availableConnections.poll).filter(_.isOpen).map(Future.successful).getOrElse {
      val i = liveConnections.incrementAndGet()
      if(i <= maxConnections) {
        bootstrap.connect().toFuture.
          map { channel =>
            new Connection(
              channel.asInstanceOf[SocketChannel],
              options.debug
            )
          }.
          andThen {
            case Success(c) =>
              if(!connections.offer(c)) Panic.!!!()
              c.closed.andThen { case _ => destroyConnection(c) }
            case Failure(_) =>
              liveConnections.decrementAndGet()
          }
      }
      else {
        waitConnection()
      }
    }.map { connection =>
      if(connection.concurrentUses.get > 0) Panic.!!!()
      connection
    }
  }

  def stop(): Future[Unit] = {
    closed.compareAndSet(false, true)
    waiters.asScala.foreach(_.failure(Error.ClientAlreadyClosed))
    Future.sequence(connections.asScala.map(_.close())).map { _ =>
      if(liveConnections.intValue != 0) Panic.!!!()
    }.andThen { case _ =>
      eventLoop.shutdownGracefully()
    }
  }

  def apply(request: Request): Future[Response] = {
    acquireConnection().flatMap { connection =>
      connection(request).map { case (response, release) =>
        release.discrete.takeWhile(!_).onFinalize(Task.delay {
          releaseConnection(connection)
        }).run.unsafeRunAsyncFuture()
        response
      }
    }
  }

  def apply(request: Request, followRedirects: Boolean): Future[Response] = {
    if(followRedirects) {
      def followRedirects0(request: Request): Future[Response] = {
        request match {
          case GET at _ => {
            apply(request).flatMap { response =>
              if(response.isRedirect) {
                response.drain.flatMap { _ =>
                  response.headers.get(Headers.Location).map { location =>
                    followRedirects0(request.copy(url = location.toString)(Content.empty))
                  }.getOrElse(Future.successful(response))
                }
              }
              else Future.successful(response)
            }
          }
          case _ => Future.failed(Error.AutoRedirectNotSupported)
        }
      }
      followRedirects0(request)
    }
    else apply(request)
  }

  def run[A](request: Request, followRedirects: Boolean = false)
    (f: Response => Future[A] = (_:Response) => Future.successful(())): Future[A] = {
    apply(request, followRedirects).flatMap { response =>
      f(response).
        flatMap(s => response.drain.map(_ => s)).
        recoverWith { case e =>
          response.drain.flatMap(_ => Future.failed(e))
        }
    }
  }

  def runAndStop[A](f: Client => Future[A]): Future[A] = {
    f(this).andThen { case _ => this.stop() }
  }
}

object Client {

  def apply(
    host: String,
    port: Int = 80,
    scheme: String = "http",
    ssl: SSL.Configuration = SSL.Configuration.default,
    options: ClientOptions = ClientOptions(),
    maxConnections: Int = 20,
    maxWaiters: Int = 100
  )(implicit executor: ExecutionContext) = new Client(
    host,
    port,
    scheme,
    ssl,
    options,
    maxConnections,
    maxWaiters
  )

  def run[A](
    request: Request,
    followRedirects: Boolean = false,
    options: ClientOptions = ClientOptions(ioThreads = 1)
  )
  (f: Response => Future[A] = (_: Response) => Future.successful(()))
  (implicit executor: ExecutionContext, ssl: SSL.Configuration): Future[A] = {
    request.headers.get(Headers.Host).map { hostHeader =>
      val client = hostHeader.toString.split("[:]").toList match {
        case host :: port :: Nil if Try(port.toInt).isSuccess =>
          Client(host, port.toInt, request.scheme, ssl, options)
        case _ =>
          Client(hostHeader.toString, if(request.scheme == "http") 80 else 443, request.scheme, ssl, options)
      }
      (for {
        response <- client(request, followRedirects)
        result <- f(response)
      } yield result).
      andThen { case _ => client.stop() }
    }.
    getOrElse(Future.failed(Error.HostHeaderMissing))
  }
}
