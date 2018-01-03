package lol.http

import java.util.{ Timer, TimerTask }

import scala.concurrent.{ Future, Promise, ExecutionContext }
import scala.concurrent.duration.{ FiniteDuration }

import cats.effect.{ IO }

package object internal {

  private[lol] def extract(url: String): (String, String, Int, String, Option[String]) = {
    val url0 = new java.net.URL(url)
    val path = if(url0.getPath.isEmpty) "/" else url0.getPath
    val port = url0.getPort
    val host = url0.getHost
    val scheme = url0.getProtocol
    val queryString = Option(url0.getQuery)
    (scheme, host, if(port < 0) url0.getDefaultPort else port, path, queryString)
  }

  private[lol] def guessContentType(fileName: String): String = {
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

  private[lol] lazy val timer = new Timer("lol.http.internal.timer", true)
  private[lol] def timeout[A](a: => A, duration: FiniteDuration)(implicit ec: ExecutionContext): Future[A] = {
    val e = Promise[A]
    timer.schedule(new TimerTask { def run(): Unit = e.completeWith(Future(a)) }, duration.toMillis)
    e.future
  }

  private[lol] def withTimeout[A](a: IO[A], duration: FiniteDuration, onTimeout: () => Unit = () => ())(implicit e: ExecutionContext): IO[A] = {
    IO.fromFuture(IO(Future.firstCompletedOf(Seq(a.unsafeToFuture().map(Right.apply), timeout(Left(()), duration))))).
      flatMap {
        case Right(x) =>
          IO.pure(x)
        case Left(_) =>
          onTimeout()
          IO.raiseError(Error.Timeout(duration))
      }
  }

}

package internal {
  private[lol] case class Cancellable[A](io: IO[A], cancel: () => Unit = () => ())
}
