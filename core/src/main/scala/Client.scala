package lol.http

import scala.util.{ Try, Success, Failure }
import scala.collection.JavaConverters._
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.concurrent.duration._

import io.netty.channel.{ ChannelInitializer }
import io.netty.bootstrap.{ Bootstrap }
import io.netty.channel.nio.{ NioEventLoopGroup }
import io.netty.channel.socket.{ SocketChannel }
import io.netty.channel.socket.nio.{ NioSocketChannel }

import java.util.concurrent.{ ArrayBlockingQueue, LinkedBlockingQueue }
import java.util.concurrent.atomic.{ AtomicLong, AtomicBoolean }

import internal.NettySupport._
import internal.{ withTimeout, KillableFuture }

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
 * received. If no connection is available when a new request comes, it waits for one
 * connection to become available.
 *
 * It is important that the user code completly consumes the response content stream, so
 * the connection is freed for the next request. That's why it is better to use the `run`
 * operation if possible since this one automatically drains the request upon return.
 *
 * If the request to execute does not specify an Host header, it will be automatically added
 * and set to the value of the client `host`.
 */
trait Client extends Service {

  /** The host this client is connected to. */
  def host: String

  /** The TCP port this client is connected to. */
  def port: Int

  /** The scheme used by this client (either HTTP or HTTPS if connected in SSL). */
  def scheme: String

  /** The SSL configuration used by the client. Must be provided if the server certificate is not recognized by default. */
  def ssl: SSL.ClientConfiguration

  /** The client options such as the number of IO thread used. */
  def options: ClientOptions

  /** The maximum number of TCP connections maintained with the remote server. */
  def maxConnections: Int

  /** The ExecutionContext that will be used to run the user code. */
  implicit def executor: ExecutionContext

  private class NettyClient {
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
        Option(scheme).filter(_ == "https").foreach { _ =>
          channel.pipeline.addLast("SSL", ssl.ctx.newHandler(channel.alloc()))
        }
      }
    })
    def connect() = bootstrap.connect().toFuture
    def shutdown() = {
      eventLoop.shutdownGracefully()
    }
  }
  private lazy val nettyClient = new NettyClient

  // -- Connection pool
  private lazy val closed = new AtomicBoolean(false)
  private lazy val liveConnections = new AtomicLong(0)
  private lazy val connections = new ArrayBlockingQueue[ClientConnection](maxConnections)
  private lazy val availableConnections = new ArrayBlockingQueue[ClientConnection](maxConnections)
  private lazy val waiters = new LinkedBlockingQueue[Promise[ClientConnection]]()

  /** The number of TCP connections currently opened with the remote server. */
  def openedConnections: Int = liveConnections.intValue
  private[http] def waitingConnections: Int = waiters.size

  /** Check if this client is already closed (ie. it does not accept any more requests). */
  def isClosed: Boolean = closed.get

  private def waitConnection(): KillableFuture[ClientConnection] = {
    val p = Promise[ClientConnection]
    waiters.offer(p)
    KillableFuture(
      p.future,
      () => waiters.remove(p)
    )
  }

  private def destroyConnection(c: ClientConnection): Unit = {
    availableConnections.remove(c)
    if(!connections.remove(c)) Panic.!!!()
    liveConnections.decrementAndGet()
  }

  private def releaseConnection(c: ClientConnection): Unit = {
    if(c.isOpen) Option(waiters.poll).fold {
      if(!availableConnections.offer(c)) Panic.!!!()
    } { _.success(c) }
  }

  private def acquireConnection(): KillableFuture[ClientConnection] =
    if(closed.get) KillableFuture(Future.failed(Error.ClientAlreadyClosed)) else
    Option(availableConnections.poll).filter(_.isOpen).map(c => KillableFuture(Future.successful(c))).getOrElse {
      if(liveConnections.get < maxConnections) {
        liveConnections.incrementAndGet()
        KillableFuture(
          nettyClient.connect().
            map(c => Netty.clientConnection(c.asInstanceOf[SocketChannel], options.debug)).
            andThen {
              case Success(c) =>
                if(!connections.offer(c)) Panic.!!!()
                c.closed.unsafeToFuture().andThen { case _ => destroyConnection(c) }
              case Failure(e) =>
                liveConnections.decrementAndGet()
                // If we fail obtaining a connection for any reason, let's fail all
                // the waiters with the same exception. This is because otherwise there
                // is no guarantee that we will be able to obtain a connection later and
                // thus the waiters will wait forever (up to their timeout at least); we
                // prefer having them failing fast.
                waiters.asScala.toList.foreach { waiter =>
                  waiters.remove(waiter)
                  waiter.tryFailure(e)
                }
            }
        )
      }
      else {
        waitConnection()
      }
    }

  /** Stop the client and kill all current and waiting requests.
    * @return a Future resolved as soon as the client is shutdown.
    */
  def stop(): Future[Unit] = {
    closed.compareAndSet(false, true)
    waiters.asScala.toList.foreach { waiter =>
      waiters.remove(waiter)
      waiter.tryFailure(Error.ClientAlreadyClosed)
    }
    Future.sequence(connections.asScala.map(_.close.unsafeToFuture)).map { _ =>
      if(liveConnections.intValue != 0) Panic.!!!()
    }.andThen { case _ =>
      nettyClient.shutdown()
    }
  }

  /** Send a request to the server and eventually give back the response.
    * @param request the HTTP request to be sent to the server.
    * @return eventually the HTTP response.
    */
  def apply(request: Request): Future[Response] = {
    @volatile var underlyingConnection: Option[ClientConnection] = None
    val eventuallyConnection = acquireConnection()
    KillableFuture(
      eventuallyConnection.flatMap { connection =>
        underlyingConnection = Some(connection)
        // (automatically add the Host header if not specified in the incoming request)
        val requestWithHost =
          if(request.headers.contains(h"Host")) request else request.addHeaders(h"Host" -> h"${this.host}")
        (for {
          response <- connection(requestWithHost, () => releaseConnection(connection))
        } yield response).unsafeToFuture()
      },
      () => {
        eventuallyConnection.cancel()
        underlyingConnection.foreach(_.close.unsafeRunSync)
      }
    )
  }

  /** Send a request to the server and eventually give back the response.
    * @param request the HTTP request to be sent to the server.
    * @param followRedirects if true follow the intermediate HTTP redirects. Default to true.
    * @param timeout maximum amount of time allowed to retrieve the response.
    * @return eventually the HTTP response.
    */
  def apply(request: Request, followRedirects: Boolean = true, timeout: FiniteDuration = FiniteDuration(30, "seconds")): Future[Response] = {
    val eventuallyResponse = apply(request)
    withTimeout(
      eventuallyResponse.flatMap { response =>
        if(response.isRedirect && followRedirects) {
          request match {
            case GET at _ =>
              response.drain.flatMap { _ =>
                response.headers.get(Headers.Location).map { location =>
                  apply(request.copy(url = location.toString)(Content.empty), followRedirects = true)
                }.getOrElse(Future.successful(response))
              }
            case _ =>
              Future.failed(Error.AutoRedirectNotSupported)
          }
        }
        else Future.successful(response)
      },
      timeout,
      () => {
        eventuallyResponse match {
          case KillableFuture(_, cancel) =>
            cancel()
          case _ =>
            Panic.!!!()
        }
      }
    )
  }

  /** Send this request to the server, eventually run the given function and return the result.
    * This operation ensures that the response content stream is fully read even if the provided
    * user code do not consume it. The response is drained as soon as the `f` function returns.
    * @param request the HTTP request to be sent to the server.
    * @param followRedirects if true follow the intermediate HTTP redirects. Default to true.
    * @param timeout maximum amount of time allowed to retrieve the response and extract the return value.
    * @param thunk a function that eventually receive the response and transform it to a value of type `A`.
    * @return eventually a value of type `A`.
    */
  def run[A](request: Request, followRedirects: Boolean = true, timeout: FiniteDuration = FiniteDuration(30, "seconds"))
    (thunk: Response => Future[A] = (_: Response) => Future.successful(())): Future[A] = {
    withTimeout(
      apply(request, followRedirects, timeout).
        flatMap { response =>
          thunk(response).
            flatMap(s => response.drain.map(_ => s)).
            recoverWith { case e =>
              response.drain.flatMap(_ => Future.failed(e))
            }
        },
      timeout
    )
  }

  /** Run the given function and close the client.
    * @param thunk a function that take a client and eventually return a value of type `A`.
    * @return eventually a value of type `A`.
    */
  def runAndStop[A](thunk: Client => Future[A]): Future[A] = {
    thunk(this).andThen { case _ => this.stop() }
  }

  override def toString = {
    s"Client(host=$host, port=$port, ssl=$ssl, options=$options, maxConnections=$maxConnections, " +
    s"openedConnections=$openedConnections, waitingConnections= $waitingConnections, isClosed=$isClosed)"
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
    * @param executor the [[scala.concurrent.ExecutionContext ExecutionContext]] to use to run user code.
    * @return an HTTP client instance.
    */
  def apply(
    host: String,
    port: Int = 80,
    scheme: String = "http",
    ssl: SSL.ClientConfiguration = SSL.ClientConfiguration.default,
    options: ClientOptions = ClientOptions(),
    maxConnections: Int = 10
  )(implicit executor: ExecutionContext): Client = {
    val (host0, port0, scheme0, ssl0, options0, maxConnections0, executor0) = (
      host, port, scheme, ssl, options, maxConnections, executor
    )
    new Client {
      val host = host0
      val port = port0
      val scheme = scheme0
      val ssl = ssl0
      val options = options0
      val maxConnections = maxConnections0
      implicit val executor = executor0
    }
  }

  /** Run the provided request with a temporary client, and apply the thunk function to the response.
    * @param request the request to run. It must include a proper `Host` header.
    * @param followRedirects if true follow the intermediate HTTP redirects. Default to true.
    * @param timeout maximum amount of time allowed to retrieve the response and extract the return value.
    * @param options the client options to use for the temporary client.
    * @param thunk a function that eventually receive the response and transform it to a value of type `A`.
    * @return eventually a value of type `A`.
    */
  def run[A](
    request: Request,
    followRedirects: Boolean = true,
    timeout: FiniteDuration = FiniteDuration(30, "seconds"),
    options: ClientOptions = ClientOptions(ioThreads = 1)
  )
  (thunk: Response => Future[A] = (_: Response) => Future.successful(()))
  (implicit executor: ExecutionContext, ssl: SSL.ClientConfiguration): Future[A] = {
    request.headers.get(Headers.Host).map { hostHeader =>
      val client = hostHeader.toString.split("[:]").toList match {
        case host :: port :: Nil if Try(port.toInt).isSuccess =>
          Client(host, port.toInt, request.scheme, ssl, options)
        case _ =>
          Client(hostHeader.toString, if(request.scheme == "http") 80 else 443, request.scheme, ssl, options)
      }
      withTimeout(
        (for {
          response <- client(request, followRedirects, timeout)
          result <- thunk(response)
        } yield result).
        andThen { case _ => client.stop() },
        timeout
      )
    }.
    getOrElse(Future.failed(Error.HostHeaderMissing))
  }
}
