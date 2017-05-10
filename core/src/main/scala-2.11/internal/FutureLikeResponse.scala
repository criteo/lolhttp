package lol.http.internal

import lol.http.Response

import scala.concurrent.duration.Duration
import scala.concurrent.{CanAwait, ExecutionContext, Future}
import scala.util.{Success, Try}

trait FutureLikeResponse extends Future[Response] {
  self: Response =>

  def onComplete[U](f: Try[Response] => U)(implicit executor: ExecutionContext) = executor.execute(new Runnable {
    override def run = f(Success(self))
  })

  override def isCompleted: Boolean = true

  override def value: Option[Try[Response]] = Some(Success(this))

  override def transform[S](s: Response => S, f: (Throwable) => Throwable)(implicit executor: ExecutionContext): Future[S] =
    Future(s(this))

  override def ready(atMost: Duration)(implicit permit: CanAwait): FutureLikeResponse.this.type = this

  override def result(atMost: Duration)(implicit permit: CanAwait): Response = this
}
