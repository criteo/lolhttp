package lol.http

import scala.util.Try
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

import org.http4s.blaze.http._
import org.http4s.blaze.channel.nio2.ClientChannelFactory
import org.http4s.blaze.pipeline.{Command, TailStage, LeafBuilder}
import org.http4s.blaze.pipeline.stages.SSLStage

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import javax.net.ssl.{SNIHostName, SNIServerName}

import cats.implicits._

import cats.effect.{ContextShift, IO, Timer}
import cats.effect.concurrent.Semaphore

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
  private lazy val emptyBuffer = ByteBuffer.allocate(0)
  private lazy val factory = new ClientChannelFactory()
  private lazy val address = new InetSocketAddress(host, port)
  private lazy val lock = Semaphore[IO](maxConnections.toLong).unsafeRunSync
  private lazy val connections = new LinkedBlockingQueue[HttpClientSession](maxConnections)

  implicit private lazy val cs: ContextShift[IO] = IO.contextShift(executor)
  implicit private lazy val timer: Timer[IO] = IO.timer(executor)

  private def newHttp1Connection() = {
    val clazz = Class.forName("org.http4s.blaze.http.http1.client.Http1ClientStage")
    val defaultConstructor = clazz.getDeclaredConstructors().head
    defaultConstructor.setAccessible(true)
    defaultConstructor.newInstance(HttpClientConfig.Default).asInstanceOf[TailStage[ByteBuffer]]
  }

  private def getConnection(): IO[HttpClientSession] =
    for {
      _ <- if(closed.get) IO.raiseError(Error.ClientAlreadyClosed) else IO.unit
      _ <- lock.acquire
      connection <-
        IO.fromFuture(IO(factory.connect(address).map { head =>
          val connection = newHttp1Connection()
          var builder = LeafBuilder(connection)
          if(scheme.toLowerCase == "https") {
            val sslParameters = ssl.engine.getSSLParameters()
            val sniList = List(new SNIHostName(host): SNIServerName).asJava
            sslParameters.setServerNames(sniList)
            ssl.engine.setSSLParameters(sslParameters)
            builder = builder.prepend(new SSLStage(ssl.engine))
          }
          builder.base(head)
          head.sendInboundCommand(Command.Connected)
          connections.add(connection.asInstanceOf[HttpClientSession])
          connection.asInstanceOf[HttpClientSession]
        })).recoverWith {
          case e: Throwable =>
            lock.release >> IO.raiseError(e)
        }
    } yield connection

  private def releaseConnection(connection: HttpClientSession): IO[Unit] =
    for {
      _ <- IO {
        connections.remove(connection)
        connection.closeNow()
      }
      _ <- lock.release
    } yield ()

  private val closed = new AtomicBoolean(false)

  /** The host this client is connected to. */
  def host: String

  /** The  port this client is connected to. */
  def port: Int

  /** The scheme used by this client (either HTTP or HTTPS if connected in SSL). */
  def scheme: String

  /** The SSL configuration used by the client. Must be provided if the server certificate is not recognized by default. */
  def ssl: SSL.ClientConfiguration

  /** The protocol used by this client. */
  def protocol: String

  /** The maximum number of TCP connections maintained with the remote server. */
  def maxConnections: Int

  /** The ExecutionContext that will be used to run the user code. */
  implicit def executor: ExecutionContext

  /** The number of TCP connections currently opened with the remote server. */
  def openedConnections: Int = connections.size

  /** Check if this client is already closed (ie. it does not accept any more requests). */
  def isClosed: Boolean = closed.get

  /** Stop the client and kill all current and waiting requests.
    * This operation returns an `IO` and is lazy. If you want to stop
    * the client immediatly and synchronously use [[stopSync]] instead.
    * @return a IO effect resolved as soon as the client is shutdown.
    */
  def stop(): IO[Unit] = {
    closed.set(true)
    IO.fromFuture(IO(connections.asScala.toList.map(_.closeNow()).sequence)) >> IO.unit
  }

  /** Stop the client and kill all current and waiting requests right away. */
  def stopSync(): Unit = stop().unsafeRunSync

  /** Send a request to the server and eventually give back the response.
    * @param request the HTTP request to be sent to the server.
    * @return eventually the HTTP response.
    */
  def apply(request: Request): IO[Response] =
    for {
      // Get a free connection for this request
      connection <- getConnection()
      // We use synchronous queue for inbound & outbound trafic, so
      // backpressure is fully managed by user code.
      inboundQueue <- fs2.concurrent.Queue.synchronous[IO, ByteBuffer]
      outboundQueue <- fs2.concurrent.Queue.synchronous[IO, ByteBuffer]
      // Asynchronously write the response body to the outbound queue
      _ <-
        (request.content.stream
          .handleErrorWith {
            case e: Throwable =>
              fs2.Stream.eval(outboundQueue.enqueue1(emptyBuffer) >> IO.raiseError(e))
          }
          .chunks.evalMap[IO, Unit] {
            case chunk =>
              inboundQueue.enqueue1(ByteBuffer.wrap(chunk.toArray))
          } ++ fs2.Stream.eval[IO, Unit](inboundQueue.enqueue1(emptyBuffer))).compile.drain.runAsync(_ => IO.unit).toIO
      // Send the request and get the response headers
      releasableResponse <-
        IO.fromFuture(IO(
          connection.dispatch(HttpRequest(
            request.method.toString,
            if(request.url.startsWith("http://") || request.url.startsWith("https://"))
              request.url
            else
              s"${scheme}://${host}:${port}${if(request.url.startsWith("/")) "" else "/"}${request.url}",
            if(protocol == HTTP2) 2 else 1,
            if(protocol == HTTP2) 0 else 1,
            (request.content.headers ++ request.headers).toSeq.map {
              case (key, value) =>
                (key.toString, value.toString)
            },
            new BodyReader {
              def discard() = ()
              def apply() = inboundQueue.dequeue1.unsafeToFuture
              def isExhausted = false
            }
          ))
        ))
      // Read asynchronously the response body (if any) in the outbound queue
      responseBytes = releasableResponse.body
      _ <-
        fs2.Stream.eval(
          for {
            chunk <-
              IO.fromFuture(IO(responseBytes())).recoverWith {
                case e: Throwable =>
                  outboundQueue.enqueue1(emptyBuffer) >> IO.raiseError(e)
              }
            hasRemaining = chunk.hasRemaining
            _ <- outboundQueue.enqueue1(chunk)
          } yield hasRemaining
        ).takeWhile(identity).repeat.compile.drain.runAsync(_ => IO.unit).toIO
      readers <- Semaphore[IO](1)
      response =
        Response(
          releasableResponse.code,
          Content(
            fs2.Stream.
              // The content stream can be read only once
              eval(readers.tryAcquire).flatMap {
                case false =>
                  fs2.Stream.raiseError[IO](Error.StreamAlreadyConsumed)
                case true =>
                  outboundQueue.dequeue
                    .takeWhile(_.hasRemaining)
                    .map(buf => fs2.Chunk.byteBuffer(buf))
                    .flatMap(bytes => fs2.Stream.chunk(bytes))
                    .onFinalize(
                      for {
                        _ <- IO(releasableResponse.release())
                        _ <- releaseConnection(connection)
                      } yield ()
                    )
              },
            releasableResponse.headers.collect {
              case (key, value) if key.toLowerCase.startsWith("content-") =>
                (HttpString(key), HttpString(value))
            }.toMap
          ),
          releasableResponse.headers.map {
            case (key, value) =>
              (HttpString(key), HttpString(value))
          }.toMap
        )
    } yield response

  /** Send a request to the server and eventually give back the response.
    * @param request the HTTP request to be sent to the server.
    * @param timeout maximum amount of time allowed to retrieve the response.
    * @return eventually the HTTP response.
    */
  def apply(request: Request, timeout: FiniteDuration = FiniteDuration(30, "seconds")): IO[Response] =
    apply(request).timeout(timeout)

  /** Send this request to the server, eventually run the given function and return the result.
    * This operation ensures that the response content stream is fully read even if the provided
    * user code do not consume it. The response is drained as soon as the `f` function returns.
    * @param request the HTTP request to be sent to the server.
    * @param timeout maximum amount of time allowed to retrieve the response and extract the return value.
    * @param f a function that eventually receive the response and transform it to a value of type `A`.
    * @return eventually a value of type `A`.
    */
  def run[A](request: Request, timeout: FiniteDuration = FiniteDuration(30, "seconds"))
    (f: Response => IO[A] = (_: Response) => IO.unit): IO[A] =
      apply(request, timeout).
        flatMap { response =>
          f(response).
            flatMap(s => response.drain.map(_ => s)).
            attempt.flatMap {
              case Left(e) =>
                response.drain.flatMap(_ => IO.raiseError(e))
              case Right(response) =>
                IO.pure(response)
            }
        }

  /** Send this request to the server, eventually run the given function and return the result.
    * This operation ensures that the response content stream is fully read even if the provided
    * user code do not consume it. The response is drained as soon as the `f` function returns.
    * This operation is block and the calling thread will wait for the call to be completed.
    * @param request the HTTP request to be sent to the server.
    * @param timeout maximum amount of time allowed to retrieve the response and extract the return value.
    * @param f a function that eventually receive the response and transform it to a value of type `A`.
    * @return a value of type `A`.
    */
  def runSync[A](request: Request, timeout: FiniteDuration = FiniteDuration(30, "seconds"))
    (f: Response => IO[A] = (_: Response) => IO.unit): A = run(request, timeout)(f).unsafeRunSync

  /** Run the given function and close the client.
    * @param f a function that take a client and eventually return a value of type `A`.
    * @return eventually a value of type `A`.
    */
  def runAndStop[A](f: Client => IO[A]): IO[A] =
    (for {
      result <- f(this)
      _ <- stop()
    } yield result).onError { case e =>
      stop().flatMap(_ => IO.raiseError(e))
    }

  /** Run the given function and close the client. This operation is blocking and the calling
    * thread will wait for the call to be completed.
    * @param f a function that take a client and eventually return a value of type `A`.
    * @return a value of type `A`.
    */
  def runAndStopSync[A](f: Client => IO[A]): A = runAndStop(f).unsafeRunSync

  override def toString = {
    s"Client(host=$host, port=$port, ssl=$ssl, protocol=$protocol, maxConnections=$maxConnections, " +
    s"openedConnections=$openedConnections, isClosed=$isClosed)"
  }
}

/** Build HTTP clients.
  *
  * {{{
  * val client = Client("github.com")
  * val fetchGithubHomePage: IO[String] =
  *   for {
  *     homePage <- client.run(Get("/"))(_.readAs[String])
  *     _ <- client.stop()
  *   } yield (homePage)
  * println(fetchGithubHomePage.unsafeRunSync)
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
    * @param protocol the protocol to use for this client (HTTP or HTTP/2).
    * @param maxConnections the maximum number of TCP connections to maintain with the remote server.
    * @param executor the `scala.concurrent.ExecutionContext` to use to run user code.
    * @return an HTTP client instance.
    */
  def apply(
    host: String,
    port: Int = 80,
    scheme: String = "http",
    ssl: SSL.ClientConfiguration = SSL.ClientConfiguration.default,
    protocol: String = HTTP,
    maxConnections: Int = 10
  )(implicit executor: ExecutionContext): Client = {
    val (host0, port0, scheme0, ssl0, protocol0, maxConnections0, executor0) = (
      host, port, scheme, ssl, protocol, maxConnections, executor
    )
    new Client {
      val host = host0
      val port = port0
      val scheme = scheme0
      val ssl = ssl0
      val protocol = protocol0
      val maxConnections = maxConnections0
      implicit val executor = executor0
    }
  }

  /** Run the provided request with a temporary client, and apply the f function to the response.
    * @param request the request to run. It must include a proper `Host` header.
    * @param followRedirects if true follow the intermediate HTTP redirects. Default to true.
    * @param timeout maximum amount of time allowed to retrieve the response and extract the return value.
    * @param protocol the protocol to use for this client (HTTP or HTTP/2).
    * @param f a function that eventually receive the response and transform it to a value of type `A`.
    * @return eventually a value of type `A`.
    */
  def run[A](
    request: Request,
    followRedirects: Boolean = true,
    timeout: FiniteDuration = FiniteDuration(30, "seconds"),
    protocol: String = HTTP
  )
  (f: Response => IO[A] = (_: Response) => IO.unit)
  (implicit executor: ExecutionContext, ssl: SSL.ClientConfiguration): IO[A] = {
    implicit val ctx = IO.contextShift(executor)

    if(request.headers.get(Headers.Host).isEmpty) new Exception().printStackTrace()
    request.headers.get(Headers.Host).map { hostHeader =>
      val client = hostHeader.toString.split("[:]").toList match {
        case host :: port :: Nil if Try(port.toInt).isSuccess =>
          Client(host, port.toInt, request.scheme, ssl, protocol)
        case _ =>
          Client(hostHeader.toString, if(request.scheme == "http") 80 else 443, request.scheme, ssl, protocol)
      }
      (for {
        response <- client(request, timeout)
        result <-
          if(response.isRedirect && followRedirects) {
            request match {
              case GET at _ =>
                response.drain.flatMap { _ =>
                  response.headers.get(Headers.Location).map { location =>
                    val redirectUrl =
                      if(location.toString.startsWith("http://") || location.toString.startsWith("https://"))
                        location.toString
                      else
                        s"${client.scheme}://${client.host}:${client.port}${if(location.toString.startsWith("/")) "" else "/"}${location}"
                    run(Get(redirectUrl), true, timeout, protocol)(f)
                  }.getOrElse(f(response))
                }
              case _ =>
                IO.raiseError(Error.AutoRedirectNotSupported)
            }
          }
          else f(response)
        _ <- client.stop()
      } yield result).onError { case e =>
        client.stop().flatMap(_ => IO.raiseError(e))
      }
    }.
    getOrElse(IO.raiseError(Error.HostHeaderMissing))
  }

  /** Run the provided request with a temporary client, and apply the f function to the response.
    * This operation is blocking and the calling thread will wait for the request to be completed.
    * @param request the request to run. It must include a proper `Host` header.
    * @param followRedirects if true follow the intermediate HTTP redirects. Default to true.
    * @param timeout maximum amount of time allowed to retrieve the response and extract the return value.
    * @param protocol the protocol to use for this client (HTTP or HTTP/2).
    * @param f a function that eventually receive the response and transform it to a value of type `A`.
    * @return synchronously a value of type `A`.
    */
  def runSync[A](
    request: Request,
    followRedirects: Boolean = true,
    timeout: FiniteDuration = FiniteDuration(30, "seconds"),
    protocol: String = HTTP
  )
  (f: Response => IO[A] = (_: Response) => IO.unit)
  (implicit executor: ExecutionContext, ssl: SSL.ClientConfiguration): A = run(request, followRedirects, timeout, protocol)(f).unsafeRunSync
}
