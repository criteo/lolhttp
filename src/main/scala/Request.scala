package lol.http

import scala.concurrent.{ Future }

import fs2.{ Task, Stream }

case class Request(
  method: HttpMethod,
  url: String = "/",
  scheme: String = "http",
  content: Content = Content.empty,
  headers: Map[HttpString,HttpString] = Map.empty
) {

  private lazy val (p, qs) = url.split("[?]").toList match {
    case p :: qs :: Nil => (p, Some(qs))
    case p :: Nil => (p, None)
    case _ => sys.error(s"Invalid URL $url")
  }
  lazy val path = p
  lazy val queryString = qs

  // Content
  def apply[A: ContentEncoder](content: A) = copy(content = Content.of(content))
  def read[A: ContentDecoder]: Future[A] = content.as[A].unsafeRunAsyncFuture
  def read[A](effect: Stream[Task,Byte] => Task[A]): Future[A] = effect(content.stream).unsafeRunAsyncFuture
  def drain: Future[Unit] = read(_.onError {
    case e: Throwable if e == Error.StreamAlreadyConsumed => Stream.empty
    case e: Throwable => Stream.fail(e)
  }.drain.run)


  // Headers
  def addHeaders(headers: Map[HttpString,HttpString]) = copy(headers = this.headers ++ headers)
  def addHeaders(headers: (HttpString,HttpString)*) = copy(headers = this.headers ++ headers.toMap)
  def removeHeader(header: HttpString) = copy(headers = this.headers - header)
}

class HttpMethod(verb: String) {
  override def toString = verb.toUpperCase
  override def equals(other: Any) = other match {
    case other: HttpMethod => verb.toString == other.toString
    case _ => false
  }
}
object HttpMethod {
  def apply(verb: String) = new HttpMethod(verb.toUpperCase)
  def unapply(method: HttpMethod) = Some(method.toString)
}

object at {
  def unapply[A](req: Request): Option[(HttpMethod, String)] = Some(req.method -> req.url)
}
