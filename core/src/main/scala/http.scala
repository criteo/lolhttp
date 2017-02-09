package lol

import fs2.{ Stream, Task}

import scala.concurrent.{ Future }

package object http {
  type Service = Request => Future[Response]
  type PartialService = PartialFunction[Request,Future[Response]]

  // Default HttpMethods
  val GET = HttpMethod("GET")
  val HEAD = HttpMethod("HEAD")
  val POST = HttpMethod("POST")
  val PUT = HttpMethod("PUT")
  val DELETE = HttpMethod("DELETE")

  // Response builders
  def Ok = Response(200)
  def Created = Response(201)
  def InternalServerError = Response(500)
  def Redirect(url: String, permanent: Boolean = false) = {
    Response(if(permanent) 308 else 307).addHeaders(Headers.Location -> HttpString(url))
  }
  def NotFound = Response(404)
  def BadRequest = Response(400)
  def SwitchingProtocol(protocol: HttpString, upgradeConnection: (Stream[Task,Byte]) => Stream[Task,Byte]) = {
    Response(101, upgradeConnection = upgradeConnection).
      addHeaders(Headers.Upgrade -> protocol, Headers.Connection -> h"Upgrade")
  }
  def UpgradeRequired(protocol: HttpString) = Response(426).addHeaders(Headers.Upgrade -> protocol)

  // Request builders
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
  def Get(url: String) = request(url)
  def Head(url: String) = request(url).copy(method = HEAD)
  def Delete(url: String) = request(url).copy(method = DELETE)
  def Post[A](url: String, content: A)(implicit encoder: ContentEncoder[A]) = {
    request(url).copy(method = POST, content = encoder(content))
  }
  def Put[A](url: String, content: A)(implicit encoder: ContentEncoder[A]) = {
    request(url).copy(method = PUT, content = encoder(content))
  }

  // Utilities
  implicit class StringInterpolations(ctx: StringContext) {
    object url {
      def apply(args: Any*): String = ctx.s(args:_*)
      def unapplySeq(req: Request): Option[Seq[String]] = unapplySeq(req.url)
      def unapplySeq(url: String): Option[Seq[String]] = {
        ctx.parts.mkString("([^/]*)").replace("?", "[?]").r.unapplySeq(url)
      }
    }
    object h {
      def apply(args: Any*): HttpString = HttpString(ctx.s(args:_*))
      def unapplySeq(hString: HttpString): Option[Seq[Unit]] = {
        if(hString == ctx.s()) Some(Nil) else None
      }
    }
  }
}
