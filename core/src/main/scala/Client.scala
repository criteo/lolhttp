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

/** Allow to configure an HTTP client.
  * @param ioThreads the number of threads used for the IO work. Default to `min(availableProcessors, 2)`.
  * @param tcpNoDelay if true disable Nagle's algorithm. Default `true`.
  * @param bufferSize if defined used as a hint for the TCP buffer size. If none use the system default. Default to `None`.
  * @param debug if defined log the TCP traffic with the provided logger name. Default to `None`.
  */
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

  private var upgrading: Option[() => Unit] = None

  // used for internal sanity check
  val id = Connection.connectionIds.incrementAndGet()
  val concurrentUses = new AtomicLong(0)

  debug.foreach { logger =>
    channel.pipeline.addLast("Debug", new LoggingHandler(s"$logger.$id", LogLevel.INFO))
  }
  channel.pipeline.addLast("Http", new HttpClientCodec())
  channel.pipeline.addLast("HttpDecompress", new HttpContentDecompressor())
  channel.pipeline.addLast("CatchAll", new SimpleChannelInboundHandler[Any] {
    override def channelRead0(ctx: ChannelHandlerContext, msg: Any) = Panic.!!!(s"Missed $msg")
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
  def isOpen: Boolean = channel.isActive

  def apply(request: Request): Future[(Response, async.immutable.Signal[Task,Boolean])] = {
    if(concurrentUses.incrementAndGet() != 1) Panic.!!!()

    val (content, readers, releaseConnection) = (for {
      c <- async.synchronousQueue[Task,Chunk[Byte]]
      r <- async.semaphore[Task](1)
      e <- async.signalOf[Task,Boolean](false)
    } yield (c,r,e)).unsafeRun()

    var buffer: Chunk[Byte] = Chunk.empty
    var responseDone = false
    var finalized = false

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
    (request.content.headers ++ request.headers).foreach { case (key,value) =>
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
            if(upgrading.nonEmpty) Panic.!!!()
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
                          (upstream to channel.bytesSink).run.unsafeRunAsyncFuture().onComplete(_.get)

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
              upgrading = Some(() => {
                // This is not an HTTP connection anymore
                channel.pipeline.remove("ResponseHandler")
                channel.pipeline.remove("HttpDecompress")
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
                      } yield ()).unsafeRunAsyncFuture().onComplete(_.get)
                    }
                    override def channelInactive(ctx: ChannelHandlerContext) = {
                      content.enqueue1(Chunk.empty).unsafeRunAsyncFuture().onComplete(_.get)
                    }
                  }
                )
              })
            }
          case part: HttpContent =>
            buffer = Chunk.concat(Seq(buffer, part.content.toChunk))
            if(part.isInstanceOf[LastHttpContent]) {
              upgrading.fold(responseDone = true)(_.apply())
            }
          case _ =>
            Panic.!!!()
        }
      }
      override def channelReadComplete(ctx: ChannelHandlerContext) = {
        if(!finalized) (for {
          _ <- if(buffer.nonEmpty) content.enqueue1(buffer) else Task.now(())
          _ <- if(responseDone) content.enqueue1(Chunk.empty) else Task.now(())
          _ <- Task.fromFuture {
            channel.runInEventLoop {
              if(responseDone) {
                val response = eventuallyResponse.future.value.flatMap(_.toOption).getOrElse(Panic.!!!())
                if(response.headers.get(Headers.Connection).exists(_ == h"Close") || request.headers.get(Headers.Connection).exists(_ == h"Close")) {
                  // Close the connection if requested by the protocol
                  channel.close()
                }
                else {
                  // Prepare the connection for the next request
                  Try(channel.pipeline.remove("ResponseHandler")) match {
                    case Failure(_) =>
                      channel.close()
                    case _ =>
                      channel.config.setAutoRead(true)
                  }
                }
                if(concurrentUses.decrementAndGet() != 0) Panic.!!!()
                releaseConnection.set(true).unsafeRun()
                finalized = true
              }
              else {
                buffer = Chunk.empty
                channel.read()
              }
            }
          }
        } yield ()).unsafeRunAsyncFuture().onComplete(_.get)
      }
    })

    channel.config.setAutoRead(false)
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

/** An HTTP client.
 *
 * {{{
 * val eventuallyContent = client(Get("/hello")).flatMap { response =>
 *   response.readAs[String]
 * }
 * }}}
 *
 * An HTTP client is a [[Service]] function. It handles HTTP requests
 * and eventually returns HTTP responses.
 *
 * A client maintains several TCP connections to the remote server. These connections
 * are used to send requests and are blocked until the corresponding response has been
 * received. If no connection is available when a new request comes, it is pushed to a
 * bounded queue of `maxWaiters` size. As soon as this queue is full, the client starts
 * rejecting new requests.
 *
 * It is important that the user code completly consumes the response content stream, so
 * the connection is freed for the next request. That's why it is better to use the `run`
 * operation if possible since this one automatically drains the request upon return.
 */
trait Client extends Service {

  /** The host this client is connected to. */
  def host: String

  /** The TCP port this client is connected to. */
  def port: Int

  /** The scheme used by this client (either HTTP or HTTPS if connected in SSL). */
  def scheme: String

  /** The SSL configuration used by the client. Must be provided if the server certificate is not recognized by default. */
  def ssl: SSL.Configuration

  /** The client options such as the number of IO thread used. */
  def options: ClientOptions

  /** The maximum number of TCP connections maintained with the remote server. */
  def maxConnections: Int

  /** The maximum number of waiting requests before the client starts rejecting new ones. */
  def maxWaiters: Int

  /** The ExecutionContext that will be used to run the user code. */
  implicit def executor: ExecutionContext

  private lazy val eventLoop = new NioEventLoopGroup(options.ioThreads)
  private lazy val bootstrap = new Bootstrap().
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
      Option(scheme).filter(_ == "https").foreach { _ =>
        val sslCtx = new JdkSslContext(ssl.ctx, true, ClientAuth.NONE)
        channel.pipeline.addLast("SSL", sslCtx.newHandler(channel.alloc()))
      }
    }
  })

  // -- Connection pool
  private lazy val closed = new AtomicBoolean(false)
  private lazy val liveConnections = new AtomicLong(0)
  private lazy val connections = new ArrayBlockingQueue[Connection](maxConnections)
  private lazy val availableConnections = new ArrayBlockingQueue[Connection](maxConnections)
  private lazy val waiters = new ArrayBlockingQueue[Promise[Connection]](maxWaiters)

  /** The number of TCP connections currently opened with the remote server. */
  def openedConnections: Int = liveConnections.intValue

  /** Check if this client is already closed (ie. it does not accept any more requests). */
  def isClosed: Boolean = closed.get

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

  /** Stop the client and kill all current and waiting requests.
    * @return a Future resolved as soon as the client is shutdown.
    */
  def stop(): Future[Unit] = {
    closed.compareAndSet(false, true)
    waiters.asScala.foreach(_.failure(Error.ClientAlreadyClosed))
    Future.sequence(connections.asScala.map(_.close())).map { _ =>
      if(liveConnections.intValue != 0) Panic.!!!()
    }.andThen { case _ =>
      eventLoop.shutdownGracefully()
    }
  }

  /** Send a request to the server and eventually give back the response.
    * @param request the HTTP request to be sent to the server.
    * @return eventually the HTTP response.
    */
  def apply(request: Request): Future[Response] = {
    acquireConnection().flatMap { connection =>
      connection(request).map { case (response, release) =>
        release.discrete.takeWhile(!_).onFinalize(Task.delay {
          releaseConnection(connection)
        }).run.unsafeRunAsyncFuture().onComplete(_.get)
        response
      }
    }
  }

  /** Send a request to the server and eventually give back the response.
    * @param request the HTTP request to be sent to the server.
    * @param followRedirects if true follow the intermediate HTTP redirects.
    * @return eventually the HTTP response.
    */
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

  /** Send this request to the server, eventually run the given function and return the result.
    * This operation ensures that the response content stream is fully read even if the provided
    * user code do not consume it. The response is drained as soon as the `f` function returns.
    * @param request the HTTP request to be sent to the server.
    * @param followRedirects if true follow the intermediate HTTP redirects.
    * @param script a function that eventually receive the response and transform it to a value of type `A`.
    * @return eventually a value of type `A`.
    */
  def run[A](request: Request, followRedirects: Boolean = false)
    (script: Response => Future[A] = (_: Response) => Future.successful(())): Future[A] = {
    apply(request, followRedirects).flatMap { response =>
      script(response).
        flatMap(s => response.drain.map(_ => s)).
        recoverWith { case e =>
          response.drain.flatMap(_ => Future.failed(e))
        }
    }
  }

  /** Run the given function and close the client.
    * @param script a function that take a client and eventually return a value of type `A`.
    * @return eventually a value of type `A`.
    */
  def runAndStop[A](script: Client => Future[A]): Future[A] = {
    script(this).andThen { case _ => this.stop() }
  }

  override def toString = {
    s"Client(host=$host, port=$port, ssl=$ssl, options=$options, maxConnections=$maxConnections, " +
    s"maxWaiters=$maxWaiters, openedConnections=$openedConnections, isClosed=$isClosed)"
  }
}

/** Build HTTP clients.
  *
  * {{{
  * val client = Client("github.com")
  * val homePage = client.run(Get("/"))(_.readAs[String])
  * client.stop()
  * }}}
  *
  * Once created an HTTP client maintains several TCP connections to the remote server, and
  * can be reused to run several requests. It is better to create a dedicated client this way if 
  * you plan to send many requests to the same server.
  *
  * However there are some situations where you have a single request to run, or you have a batch
  * of requests to send over an unknown set of servers. In this case you can use the [[run]] operation
  * that automatically create a temporary HTTP client to run the request and trash it after the exchange
  * completion. 
  *
  * {{{
  * val homePage = Client.run(Get("http://github.com/"))(_.readAs[String])
  * }}}
  *
  * Note that in this case, for each request, a new client (including the whole IO
  * infrastructure) will to be created, and a new TCP connection will be opened to the server.
  */
object Client {

  /** Create a new HTTP Client for the provided host/port.
    * @param host the host to use to setup the TCP connections.
    * @param port the port to use to setup the TCP connections.
    * @param scheme either __http__ or __https__.
    * @param ssl if provided the custom SSL configuration to use for this client.
    * @param options the client options such as the number of IO thread used.
    * @param maxConnections the maximum number of TCP connections to maintain with the remote server.
    * @param maxWaiters the maximum number of requests waiting for an available connection.
    * @param executor the [[scala.concurrent.ExecutionContext ExecutionContext]] to use to run user code.
    * @return an HTTP client instance.
    */
  def apply(
    host: String,
    port: Int = 80,
    scheme: String = "http",
    ssl: SSL.Configuration = SSL.Configuration.default,
    options: ClientOptions = ClientOptions(),
    maxConnections: Int = 10,
    maxWaiters: Int = 100
  )(implicit executor: ExecutionContext): Client = {
    val (host0, port0, scheme0, ssl0, options0, maxConnections0, maxWaiters0, executor0) = (
      host, port, scheme, ssl, options, maxConnections, maxWaiters, executor
    )
    new Client {
      val host = host0
      val port = port0
      val scheme = scheme0
      val ssl = ssl0
      val options = options0
      val maxConnections = maxConnections0
      val maxWaiters = maxWaiters0
      implicit val executor = executor0
    }
  }

  /** Run the provided request with a temporary client, and apply the script function to the response.
    * @param request the request to run. It must include a proper `Host` header.
    * @param followRedirects if true follow the intermediate HTTP redirects.
    * @param options the client options to use for the temporary client.
    * @param script a function that eventually receive the response and transform it to a value of type `A`.
    * @return eventually a value of type `A`.
    */
  def run[A](
    request: Request,
    followRedirects: Boolean = false,
    options: ClientOptions = ClientOptions(ioThreads = 1)
  )
  (script: Response => Future[A] = (_: Response) => Future.successful(()))
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
        result <- script(response)
      } yield result).
      andThen { case _ => client.stop() }
    }.
    getOrElse(Future.failed(Error.HostHeaderMissing))
  }
}
