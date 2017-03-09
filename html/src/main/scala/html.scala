package lol.html

import lol.http._

import scala.io.{ Codec }

case class Html(content: String) {
  lazy val size = content.size
}

object Html {
  implicit lazy val defaultEncoder = encoder(Codec.UTF8)
  def encoder(codec: Codec) = new ContentEncoder[Html] {
    def apply(html: Html) = {
      ContentEncoder.text(codec)(html.content).addHeaders(
        Headers.ContentType -> h"text/html; charset=${codec}"
      )
    }
  }
}

object `package` {
  implicit class HtmlString(val ctx: StringContext) {
    private def serialize(arg: Any): String = arg match {
      case Html(x) => x
      case Some(x) => serialize(x)
      case None => ""
      case x: TraversableOnce[_] => x.map(serialize).mkString
      case x => x.toString.replaceAll("<", "&lt;")
    }
    def html(args: Any*) = Html(ctx.s(args.map(serialize): _*))
  }
}