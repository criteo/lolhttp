package lol.http

import scala.concurrent.{ Future }

import fs2.{ Task, Stream }

case class Request(
  method: HttpMethod,
  path: String = "/",
  queryString: Option[String] = None,
  scheme: String = "http",
  content: Content = Content.empty,
  headers: Map[String, String] = Map.empty
) {

  // Content
  def apply[A: ContentEncoder](content: A) = copy(content = Content(content))
  def read[A: ContentDecoder]: Future[A] = content.as[A].unsafeRunAsyncFuture
  def read[A](effect: Stream[Task,Byte] => Task[A]): Future[A] = effect(content.stream).unsafeRunAsyncFuture
  def drain: Future[Unit] = read[Unit]

  // Headers
  def addHeaders(headers: Map[String,String]) = copy(headers = this.headers ++ headers)
  def addHeaders(headers: (String,String)*) = copy(headers = this.headers ++ headers.toMap)
  def removeHeader(header: String) = copy(headers = this.headers - header)

  // URL
  lazy val url = path + queryString.map(q => s"?$q").getOrElse("")
  def withUrl(url: String) = {
    val (newPath, newQueryString) = url.split("[?]").toList match {
      case p :: qs :: Nil => (p, Some(qs))
      case p :: Nil => (p, None)
      case _ => sys.error(s"Invalid URL $url")
    }
    copy(path = newPath, queryString = newQueryString)
  }
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
