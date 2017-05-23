package lol.http.internal

import scala.util._
import scala.concurrent._
import scala.concurrent.duration._

private[http] case class KillableFuture[+A](wrapped: Future[A], cancel: () => Unit) extends Future[A] {
  def ready(atMost: Duration)(implicit permit: CanAwait) = { wrapped.ready(atMost); this }
  def result(atMost: Duration)(implicit permit: CanAwait): A = wrapped.result(atMost)
  def isCompleted = wrapped.isCompleted
  def onComplete[U](f: Try[A] => U)(implicit executor: ExecutionContext): Unit = wrapped.onComplete(f)
  def value: Option[Try[A]] = wrapped.value
  override def toString = s"K$wrapped"
}
