package lol.http

import java.util.{ Timer, TimerTask }

import scala.concurrent.{ Future, Promise, ExecutionContext }
import scala.concurrent.duration.{ FiniteDuration }

package object internal {

  // Sometimes we don't want to pollute the API by asking an executionContext, so
  // we will use this one internally. It will be only used for internal non-blocking operations when
  // no user code is involved.
  val nonBlockingInternalExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  def extract(url: String): (String, String, Int, String, Option[String]) = {
    val url0 = new java.net.URL(url)
    val path = if(url0.getPath.isEmpty) "/" else url0.getPath
    val port = url0.getPort
    val host = url0.getHost
    val scheme = url0.getProtocol
    val queryString = Option(url0.getQuery)
    (scheme, host, if(port < 0) url0.getDefaultPort else port, path, queryString)
  }

  def guessContentType(fileName: String): String = {
    fileName.split("[.]").lastOption.collect {
      case "css"          => "text/css"
      case "htm" | "html" => "text/html"
      case "txt"          => "text/plain"
      case "js"           => "application/javascript"
      case "gif"          => "images/gif"
      case "png"          => "images/png"
      case "jpg" | "jpeg" => "images/jpeg"
    }.getOrElse("application/octet-stream")
  }

  val timer = new Timer("lol.http.internal.timer")
  def timeout[A](a: => A, duration: FiniteDuration): Future[A] = {
    val e = Promise[A]
    timer.schedule(new TimerTask { def run(): Unit = e.success(a) }, duration.toMillis)
    e.future
  }

  def withTimeout[A](a: Future[A], duration: FiniteDuration, onTimeout: () => Unit = () => ())(implicit e: ExecutionContext): Future[A] = {
    Future.firstCompletedOf(Seq(a.map(Right.apply), timeout(Left(()), duration))).
      flatMap {
        case Right(x) =>
          Future.successful(x)
        case Left(_) =>
          onTimeout()
          Future.failed(Error.Timeout(duration))
      }
  }

}
