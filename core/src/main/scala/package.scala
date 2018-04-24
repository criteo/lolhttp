package lol

import cats.effect.{ IO }
import fs2.{ Stream }

import scala.concurrent.{ ExecutionContext }
import scala.concurrent.duration.{ FiniteDuration }

import scala.language.implicitConversions

/** The core module for lolhttp.
  *
  * {{{
  * Server.listen(8888) { request =>
  *   Ok("Hello world!")
  * }
  *
  * Client.run(Get("http://localhost:8888/")) { response =>
  *   response.readAs[String]
  * }
  * }}}
  *
  * Provides an HTTP [[Client]] and an HTTP [[Server]]. Both client and server are [[Service]] functions.
  * A service function takes a [[Request]] and eventually returns a [[Response]]. Requests and responses
  * are shared between the client and the server API, making it easy to assemble them. Both are seen as a set
  * of [[HttpString HTTP headers]], and a [[Content]] body.
  *
  * The content [[fs2.Stream Stream]] is based on [[fs2]] and can be lazily consumed if needed.
  * It can be encoded and decoded using the appropriate [[ContentEncoder]] and [[ContentDecoder]].
  *
  * [[SSL]] is supported on both sides.
  *
  */
package object http {

  /** A service is a function from [[Request]] to eventually a [[Response]]. */
  type Service = (Request) => IO[Response]

  /** A partial service is not defined for all [[Request]]. */
  type PartialService = PartialFunction[Request,IO[Response]]

  /** Automatically convert a [[Response]] into a pure [[IO[Response]]] if needed. */
  implicit def pureResponse(response: Response): IO[Response] = IO.pure(response)

  /** Wrap a pure value a into an async effect that will be available after the `delay`. */
  def timeout[A](a: => A, delay: FiniteDuration)(implicit ec: ExecutionContext): IO[A] =
    IO.fromFuture(IO(internal.timeout(a, delay)))

  /** Protocol version for HTTP/1.1 */
  val HTTP = "HTTP/1.1"

  /** Protocol version for HTTP/2 */
  val HTTP2 = "HTTP/2"

  /** GET HTTP method. */
  val GET = HttpMethod("GET")

  /** HEAD HTTP method. */
  val HEAD = HttpMethod("HEAD")

  /** POST HTTP method. */
  val POST = HttpMethod("POST")

  /** PUT HTTP method. */
  val PUT = HttpMethod("PUT")

  /** DELETE HTTP method. */
  val DELETE = HttpMethod("DELETE")

  /** A 200 Ok [[Response]]. */
  lazy val Ok = Response(200)

  /** A 201 Created [[Response]]. */
  lazy val Created = Response(201)

  /** A 500 Internal server error [[Response]]. */
  lazy val InternalServerError = Response(500)

  /** Create 30x HTTP redirects.
    * @param url will be used as `Location` header value.
    * @param code the actual status code to use (default to 307).
    * @return a 30x [[Response]].
    */
  def Redirect(url: String, code: Int = 307) = {
    Response(code).addHeaders(Headers.Location -> HttpString(url))
  }

  /** A 404 Not found [[Response]]. */
  lazy val NotFound = Response(404)

  /** A 400 Bad request [[Response]]. */
  lazy val BadRequest = Response(400)

  /** Create a 101 Switching protocol [[Response]].
    * @param protocol the new protocol the server accepts to switch to.
    * @param upgradeConnection this function will be called with the upstream as parameter and must retun the downstream.
    * @return a 101 [[Response]].
    */
  def SwitchingProtocol(protocol: HttpString, upgradeConnection: (Stream[IO,Byte]) => Stream[IO,Byte]) = {
    Response(101, upgradeConnection = upgradeConnection).
      addHeaders(Headers.Upgrade -> protocol, Headers.Connection -> h"Upgrade")
  }

  /** Create a 426 Upgrade required [[Response]].
    * @param protocol the new protocol the server want to switch to.
    */
  def UpgradeRequired(protocol: HttpString) = Response(426).addHeaders(Headers.Upgrade -> protocol)

  private def request(url: String) = {
    if(url.startsWith("http://") || url.startsWith("https://")) {
      val (scheme, host, port, path, queryString) = internal.extract(url)
      Request(
        GET, s"""$path${queryString.map(x => "?" + x).getOrElse("")}""",scheme,
        headers = Map(Headers.Host -> h"$host:$port")
      )
    }
    else {
      Request(GET, url)
    }
  }

  /** Constructs a GET [[Request]].
    * @param url the request URL.
    * @return a [[Request]] value.
    */
  def Get(url: String) = request(url)

  /** Constructs a HEAD [[Request]].
    * @param url the request URL.
    * @return a [[Request]] value.
    */
  def Head(url: String) = request(url).copy(method = HEAD)

  /** Constructs a DELETE [[Request]].
    * @param url the request URL.
    * @return a [[Request]] value.
    */
  def Delete(url: String) = request(url).copy(method = DELETE)

  /** Constructs a POST [[Request]].
    * @param url the request URL.
    * @param content the request content body.
    * @param encoder the content encoder to use.
    * @return a [[Request]] value.
    */
  def Post[A](url: String, content: A)(implicit encoder: ContentEncoder[A]) = {
    request(url).copy(method = POST, content = encoder(content))
  }

  /** Constructs a PUT [[Request]].
    * @param url the request URL.
    * @param content the request content body.
    * @param encoder the content encoder to use.
    * @return a [[Request]] value.
    */
  def Put[A](url: String, content: A)(implicit encoder: ContentEncoder[A]) = {
    request(url).copy(method = PUT, content = encoder(content))
  }

  /** The `url` interpolation is mainly useful in pattern matching to match and extract
    * dynamic parameters in URL templates. You can use it to build URL string too, but in
    * this case it acts as the standard `s` interpolation.
    *
    * {{{
    * val userProfile = url"/users/\$userId"
    * userProfile match {
    *   case url"/users/\$userId" => println("user id" -> userId)
    *   case url"/images/\$image..." => println("image path" -> image)
    *   case url"/search?keyword=\$keyword&sort=\$sort" => println("keyword" -> keyword)
    *   case _ => println("wrong url")
    * }
    * }}}
    *
    * The matcher allow to extract dynamic parts from both the URL path and queryString. For the
    * path only a fragment or a fragment part can be extracted (no `/` will be matched):
    *
    * {{{
    * url"/users/\$id/items" = "/users/12/items" // will match with id="12"
    * url"/users/id-\$id/items" = "/users/id-12/items" // will match with id="12"
    * }}}
    *
    * If you want to capture several fragments, you can use the `...` syntax:
    *
    * {{{
    * url"/files/\$file..." = "/files/images/lol.png" // will match with file="images/lol.png"
    * }}}
    *
    * You can also match and extract from the queryString. The parameter order is not important and
    * parameters not specified in the pattern will be ignored:
    *
    * {{{
    * url"/?page=\$page&sort=\$sort" = "/?sort=asc&page=3&offset=2" // will match with page="3", sort="asc"
    * url"/?section=home" = "/?section=home&update=now" // will match
    * url"/?section=home&update=\$date" = "/?section=contact&update=now" // won't match
    * }}}
    */
  implicit class UrlMatcher(ctx: StringContext) {
    val url = internal.Url.Matcher(ctx)
  }

  /** The `h` interpolation creates [[HttpString]] values. You can use it as a matcher too.
    *
    * {{{
    * val header = h"LOCATION"
    * header match {
    *   case h"Location" => println("this is the Location header")
    *   case _ => println("wrong header")
    * }
    * }}}
    */
  implicit class LiteralHttpString(ctx: StringContext) {
    object h {
      def apply(args: Any*): HttpString = HttpString(ctx.s(args:_*))
      def unapplySeq(hString: HttpString): Option[Seq[Unit]] = {
        if(hString == ctx.s()) Some(Nil) else None
      }
    }
  }

  /** Support for Server Sent Events encoding. */
  implicit def sseEncoder[A](implicit eventEncoder: ServerSentEvents.EventEncoder[A]) = ServerSentEvents.encoder(eventEncoder)

  /** Support for Server Sent Events decoding. */
  implicit def sseDecoder[A](implicit eventDecoder: ServerSentEvents.EventDecoder[A]) = ServerSentEvents.decoder(eventDecoder)
}
