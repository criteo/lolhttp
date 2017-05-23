package lol.http.internal

import lol.http.Response

import scala.concurrent.duration.Duration
import scala.concurrent.{CanAwait, ExecutionContext, Future}
import scala.util.{Success, Try}

private[http] trait FutureLikeResponse extends Future[Response] {
  self: Response =>
  def ready(atMost: Duration)(implicit permit: CanAwait) = this

  def result(atMost: Duration)(implicit permit: CanAwait) = this

  def isCompleted = true

  def onComplete[U](f: Try[Response] => U)(implicit executor: ExecutionContext) = executor.execute(new Runnable {
    override def run = f(Success(self))
  })

  def value: Option[Try[Response]] = Some(Success(this))

  def transform[S](f: Try[Response] => Try[S])(implicit executor: ExecutionContext) = Future {
    f(Try(this)).get
  }

  def transformWith[S](f: Try[Response] => Future[S])(implicit executor: ExecutionContext) = Future {
    f(Try(this))
  }.flatten
}
