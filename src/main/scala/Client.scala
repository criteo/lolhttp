package lol.http

import scala.util.{ Try, Success, Failure }
import scala.collection.JavaConverters._
import scala.concurrent.{ ExecutionContext, Future, Promise }

import io.undertow.client.{ ClientConnection, ClientCallback, ClientRequest, ClientExchange }
import io.undertow.client.http.{ HttpClientProvider }
import io.undertow.server.{ DefaultByteBufferPool }
import io.undertow.util.{ HttpString }
import io.undertow.connector.{ ByteBufferPool }
import io.undertow.protocols.ssl.{ UndertowXnioSsl }

import org.xnio.{ OptionMap, Xnio, XnioWorker, ChannelListener }

import fs2.{ Task, Strategy }

import java.util.concurrent.{ ArrayBlockingQueue }
import java.util.concurrent.atomic.{ AtomicLong }
import java.io.{ IOException }
import java.net.{ URI }

case class ClientOptions(
  ioThreads: Int = Math.max(Runtime.getRuntime.availableProcessors, 2),
  bufferSize: Int = 16 * 1024,
  directBuffers: Boolean = true,
  tcpNoDelay: Boolean = true
)

private class Connection(
  private val client: ClientConnection,
  private val worker: XnioWorker,
  private val byteBufferPool: ByteBufferPool,
  val connectedTo: (String, Int)
)(implicit executor: ExecutionContext) {

  implicit val S = Strategy.fromExecutionContext(executor)

  private val closedPromise = Promise[Unit]
  client.addCloseListener(new ChannelListener[ClientConnection] {
    override def handleEvent(connection: ClientConnection): Unit = {
      if(!connection.isOpen) {
        closedPromise.success(())
      }
    }
  })

  lazy val closed: Future[Unit] = closedPromise.future

  def close(): Future[Unit] = {
    client.close()
    closed.andThen { case _ =>
      byteBufferPool.close()
      worker.shutdownNow()
    }
  }

  def apply(request: Request): Future[Response] = {
    val internalRequest = new ClientRequest()
    val eventuallyResponse = Promise[Response]

    internalRequest.setMethod(new HttpString(request.method.toString))
    internalRequest.setPath(s"${request.path}${request.queryString.map(q => s"?$q").getOrElse("")}")
    request.headers.foreach { case (key,value) =>
      internalRequest.getRequestHeaders.add(new HttpString(key), value)
    }

    client.sendRequest(internalRequest, new ClientCallback[ClientExchange] {
      def completed(exchange: ClientExchange) = {
        val writeContent = for {
          out <- Internal.sink(exchange.getRequestChannel)
          _ <- request.content.stream.to(out).run
        } yield ()

        writeContent.unsafeRunAsyncFuture.andThen {
          case Success(_) =>
            exchange.setResponseListener(new ClientCallback[ClientExchange] {
              def completed(exchange: ClientExchange) = {
                eventuallyResponse.tryCompleteWith {
                  (for {
                    responseStream <- Internal.source(exchange.getResponseChannel, byteBufferPool)
                  } yield {
                    exchange.getResponseChannel.resumeReads()
                    Response(
                      status = exchange.getResponse.getResponseCode,
                      headers = exchange.getResponse.getResponseHeaders.asScala.map { h =>
                        (h.getHeaderName.toString, h.pollFirst)
                      }.toMap,
                      content = Content(responseStream, None),
                      upgradeConnection = (upstream) => {
                        val stream = exchange.getConnection.performUpgrade
                        (for {
                          out <- Internal.sink(stream.getSinkChannel)
                          _ <- upstream.to(out).run
                        } yield ()).unsafeRunAsyncFuture().andThen { case _ =>
                          stream.getSinkChannel.close()
                        }
                        Internal.source(stream.getSourceChannel, byteBufferPool).unsafeRun()
                      }
                    )
                  }).unsafeRunAsyncFuture()
                }
              }
              def failed(ex: IOException) = eventuallyResponse.tryFailure(ex)
            })
          case Failure(ex) =>
            eventuallyResponse.tryFailure(ex)
        }
      }
      def failed(ex: IOException) = eventuallyResponse.tryFailure(ex)
    })
    eventuallyResponse.future
  }
}

private object Connection {
  lazy val http = new HttpClientProvider
  lazy val xnio = Xnio.getInstance(classOf[Client].getClassLoader)

  def connect(
    host: String,
    port: Int,
    scheme: String,
    ssl: SSL.Configuration,
    options: ClientOptions,
    worker: XnioWorker,
    byteBufferPool: ByteBufferPool
  )(implicit executor: ExecutionContext): Future[Connection] = {
    val eventuallyConnection = Promise[Connection]
    http.connect(
      new ClientCallback[ClientConnection] {
        def completed(connection: ClientConnection) = eventuallyConnection.success(
          new Connection(connection, worker, byteBufferPool, (host,port))
        )
        def failed(ex: IOException) = {
          worker.shutdownNow()
          byteBufferPool.close()
          eventuallyConnection.failure(ex)
        }
      },
      null,
      new URI(s"$scheme://$host:$port"),
      worker,
      new UndertowXnioSsl(xnio, OptionMap.builder.getMap, ssl.ctx),
      byteBufferPool,
      OptionMap.builder.getMap
    )
    eventuallyConnection.future
  }
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

  import Connection._

  private val worker = xnio.createWorker(OptionMap.builder.getMap)
  private val byteBufferPool = new DefaultByteBufferPool(options.directBuffers, options.bufferSize, -1, 4)

  // -- Connection pool

  private val liveConnections = new AtomicLong(0)
  private val connections = new ArrayBlockingQueue[Connection](maxConnections)
  private val availableConnections = new ArrayBlockingQueue[Connection](maxConnections)
  private val waiters = new ArrayBlockingQueue[Promise[Connection]](maxWaiters)

  def nbConnections: Int = liveConnections.intValue

  private def waitConnection(): Future[Connection] = {
    val p = Promise[Connection]
    if(waiters.offer(p)) {
      p.future
    }
    else {
      Future.failed(new Exception("This client has already too many waiting requests"))
    }
  }

  private def oops = sys.error("Incoherent connection pool state")

  private def destroyConnection(c: Connection): Unit = {
    availableConnections.remove(c)
    if(!connections.remove(c)) oops
    liveConnections.decrementAndGet()
  }

  private def releaseConnection(c: Connection): Unit = {
    Option(waiters.poll).fold {
      if(!availableConnections.offer(c)) oops
    } { _.success(c) }
  }

  private def acquireConnection(): Future[Connection] = {
    Option(availableConnections.poll).map(Future.successful).getOrElse {
      val i = liveConnections.incrementAndGet()
      if(i <= maxConnections) {
        connect(host, port, scheme, ssl, options, worker, byteBufferPool).
          andThen {
            case Success(c) =>
              if(!connections.offer(c)) oops
              c.closed.andThen { case _ => destroyConnection(c) }
            case Failure(_) =>
              liveConnections.decrementAndGet()
          }
      }
      else {
        waitConnection()
      }
    }
  }

  def stop(): Future[Unit] = {
    waiters.asScala.foreach(_.failure(new Exception("Client closed")))
    waiters.clear()
    Future.sequence(connections.asScala.map(_.close())).map { _ =>
      if(liveConnections.intValue != 0) oops
    }
  }

  def apply(request: Request): Future[Response] = {
    acquireConnection().flatMap { connection =>
      connection(request).map { response =>
        response.copy(content = response.content.copy(
          stream = response.content.stream.onFinalize(Task.delay {
            releaseConnection(connection)
          })
        ))
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
                    followRedirects0(request.withUrl(location)(Content.empty))
                  }.getOrElse {
                    Future.failed(new Exception(s"Missing `Location` header in response: $response"))
                  }
                }
              }
              else {
                Future.successful(response)
              }
            }
          }
          case _ => Future.failed(new Exception(
            "Automatic followRedirects is only allowed for GET requests"
          ))
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
        recoverWith { case e => response.drain.flatMap(_ => Future.failed(e)) }
    }
  }

  def runAndStop[A](f: Client => Future[A]): Future[A] = {
    f(this).andThen { case _ => this.stop() }
  }
}

object Client {
  java.util.logging.Logger.getLogger("org.xnio").setLevel(java.util.logging.Level.OFF)

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

  def run[A](request: Request, followRedirects: Boolean = false)
    (f: Response => Future[A] = (_: Response) => Future.successful(()))
    (implicit executor: ExecutionContext, ssl: SSL.Configuration): Future[A] = {
    request.headers.get(Headers.Host).map { hostHeader =>
      val client = hostHeader.split("[:]").toList match {
        case host :: port :: Nil if Try(port.toInt).isSuccess =>
          Client(host, port.toInt, request.scheme, ssl)
        case _ =>
          Client(hostHeader, if(request.scheme == "http") 80 else 443, request.scheme, ssl)
      }
      (for {
        response <- client(request, followRedirects)
        result <- f(response)
      } yield result).
      andThen { case _ => client.stop() }
    }.
    getOrElse {
      Future.failed(new Exception("The Host header was missing in the request"))
    }
  }
}
