package lol.http

import cats.effect.{ IO }
import fs2.{ Stream }

/** An HTTP request.
  *
  * Represent all the data available in the HTTP request headers, and the request content that
  * can be consumed lazily if needed.
  *
  * @param method the HTTP method such as GET or POST.
  * @param url the resource URL.
  * @param scheme the scheme such as `http` or `https`.
  * @param content the request content.
  * @param headers the HTTP headers.
  * @param protocol the protocol version.
  */
case class Request(
  method: HttpMethod,
  url: String = "/",
  scheme: String = "http",
  content: Content = Content.empty,
  headers: Map[HttpString,HttpString] = Map.empty,
  protocol: String = HTTP
) {

  private lazy val (p, qs) = url.split("[?]").toList match {
    case p :: qs :: Nil => (p, Some(qs))
    case p :: Nil => (p, None)
    case _ => sys.error(s"Invalid URL $url")
  }

  /** The path part of the URL (ie. without the queryString). */
  lazy val path: String = p

  /** The queryString part of the URL. */
  lazy val queryString: Option[String] = qs

  /** The queryString parsed as a sequence of key=value parameters. */
  lazy val parsedQueryString: List[(String,String)] = qs.map(internal.Url.parseQueryString).getOrElse(Nil)

  /** The queryString parameters. Duplicated parameters are ignored, only the first value is available. */
  lazy val queryStringParameters: Map[String,String] = parsedQueryString.toMap

  /** Set the content of this request.
    * @param content the content to use for this request.
    * @param encoder the [[ContentEncoder]] to use to encode this content.
    * @return a copy of this request with a new content.
    */
  def apply[A](content: A)(implicit encoder: ContentEncoder[A]) = copy(content = Content.of(content))

  /** Consume the content attached to this request and eventually produces a value of type `A`.
    * @param decoder the [[ContentDecoder]] to use to read the content.
    * @return eventually a value of type `A`.
    */
  def readAs[A](implicit decoder: ContentDecoder[A]): IO[A] = content.as[A]

  /** Consume the content attached to this request by evaluating the provided effect function.
    * @param effect the function to use to consume the stream.
    * @return eventually a value of type `A`.
    */
  def read[A](effect: Stream[IO,Byte] => IO[A]): IO[A] = effect(content.stream)

  /** Drain the content attached to this request. It is safe to call this operation even if the stream has
    * already been consumed.
    */
  def drain: IO[Unit] = read(_.handleErrorWith {
    case e: Throwable if e == Error.StreamAlreadyConsumed => Stream.empty
    case e: Throwable => Stream.raiseError(e)
  }.drain.run)

  /** Add some headers to this request.
    * @param headers the new headers to add.
    * @return a copy of the request with the new headers added.
    */
  def addHeaders(headers: Map[HttpString,HttpString]) = copy(headers = this.headers ++ headers)

  /** Add some headers to this request.
    * @param headers the new headers to add.
    * @return a copy of the request with the new headers added.
    */
  def addHeaders(headers: (HttpString,HttpString)*) = copy(headers = this.headers ++ headers.toMap)

  /** Remove some headers from this request.
    * @param headerNames the header names to remove
    * @return a copy of the request without the removed headers.
    */
  def removeHeaders(headerNames: HttpString*) = copy(headers = this.headers -- headerNames)
}

/** An HTTP method such as GET or POST.
  * @param verb the method name.
  */
class HttpMethod(verb: String) {
  override def toString = verb.toUpperCase
  override def equals(other: Any) = other match {
    case other: HttpMethod => verb.toString == other.toString
    case _ => false
  }
}

/** HTTP method matcher.
  *
  * {{{
  * request.method match {
  *   case GET => println("This is a get")
  *   case _ => println("Oops")
  * }
  * }}}
  *
  * Allow to match HTTP methods.
  */
object HttpMethod {
  def apply(verb: String) = new HttpMethod(verb.toUpperCase)
  def unapply(method: HttpMethod) = Some(method.toString)
}

/** Request extractor.
  *
  * {{{
  * val app: PartialService = {
  *   case GET at "/" =>
  *     Ok("Home")
  *   case _ =>
  *     NotFound
  * }
  * }}}
  *
  * Matches [[Request]] values by splitting them in a (HTTP method, URL) pair.
  */
object at {
  def unapply[A](req: Request): Option[(HttpMethod, String)] = Some(req.method -> req.url)
}
