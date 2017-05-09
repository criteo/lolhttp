package lol.http.internal

import scala.util._
import scala.concurrent._
import scala.concurrent.duration._

case class KillableFuture[+A](wrapped: Future[A], cancel: () => Unit) extends Future[A] {
  def ready(atMost: Duration)(implicit permit: CanAwait) = { wrapped.ready(atMost); this }
  def result(atMost: Duration)(implicit permit: CanAwait): A = wrapped.result(atMost)
  def isCompleted: Boolean = wrapped.isCompleted
  def onComplete[U](f: Try[A] => U)(implicit executor: ExecutionContext): Unit = wrapped.onComplete(f)
  def transform[S](f: Try[A] => Try[S])(implicit executor: ExecutionContext): Future[S] = KillableFuture(wrapped.transform(f), cancel)
  def transformWith[S](f: Try[A] => Future[S])(implicit executor: ExecutionContext): Future[S] = KillableFuture(wrapped.transformWith(f), cancel)
  def value: Option[Try[A]] = wrapped.value
  override def toString = s"K$wrapped"
}
