package lol.html

import lol.http._

import scala.io.{ Codec }

/** An HTML document.
  * @param the actual html text.
  */
case class Html(content: String) {

  /** The html text size. */
  lazy val size = content.size

  /** Merge this Html fragment with another one. */
  def ++(next: Html): Html = Html(content + next.content)
}

/** Provides the [[lol.http.ContentEncoder ContentEncoder]] for HTML documents. */
object Html {

  /** Encoder for HTML documents.
    * @param codec the charset to use to encode the HTML string as bytes.
    * @return a [[lol.http.ContentEncoder ContentEncoder]] for [[Html]].
    */
  def encoder(codec: Codec) = new ContentEncoder[Html] {
    def apply(html: Html) = {
      ContentEncoder.text(codec)(html.content).addHeaders(
        Headers.ContentType -> h"text/html; charset=${codec}"
      )
    }
  }

  /** Default encoder for HTML document using `UTF-8` as charset. */
  implicit lazy val defaultEncoder = encoder(Codec.UTF8)
}