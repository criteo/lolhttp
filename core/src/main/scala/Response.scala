package lol.http

import fs2.{ Stream, Task }

import scala.concurrent.{ CanAwait, Future, ExecutionContext }
import scala.concurrent.duration.{ Duration }

import scala.util.{ Success, Try }

case class Response(
  status: Int,
  content: Content = Content.empty,
  headers: Map[HttpString,HttpString] = Map.empty,
  upgradeConnection: (Stream[Task,Byte]) => Stream[Task,Byte] = _ => Stream.empty
) extends Future[Response] {

  // Content
  def apply[A: ContentEncoder](content: A) = copy(content = Content.of(content))
  def read[A: ContentDecoder]: Future[A] = content.as[A].unsafeRunAsyncFuture
  def readWith[A](effect: Stream[Task,Byte] => Task[A]): Future[A] = effect(content.stream).unsafeRunAsyncFuture
  def drain: Future[Unit] = readWith(_.onError {
    case e: Throwable if e == Error.StreamAlreadyConsumed => Stream.empty
    case e: Throwable => Stream.fail(e)
  }.drain.run)

  // Headers
  def addHeaders(headers: Map[HttpString,HttpString]) = copy(headers = this.headers ++ headers)
  def addHeaders(headers: (HttpString,HttpString)*) = copy(headers = this.headers ++ headers.toMap)
  def removeHeader(header: HttpString) = copy(headers = this.headers - header)

  // Utility
  def isRedirect = status match {
    case 301 | 302 | 303 | 307 | 308 => true
    case _ => false
  }

  // As Future
  def ready(atMost: Duration)(implicit permit: CanAwait) = this
  def result(atMost: Duration)(implicit permit: CanAwait) = this
  def isCompleted = true
  def onComplete[U](f: Try[Response] => U)(implicit executor: ExecutionContext) = executor.execute(new Runnable {
    override def run = f(Success(Response.this))
  })
  def value: Option[Try[Response]] = Some(Success(this))
  def transform[S](f: Try[Response] => Try[S])(implicit executor: ExecutionContext) = ???
  def transformWith[S](f: Try[Response] => Future[S])(implicit executor: ExecutionContext) = ???
}
