package lol.html

import lol.http._

import scala.io.{ Codec }

/** A type class to provide Html rendering. */
trait ToHtml[-A] {
  /** Print the value to [[Html]]. */
  def print(value: A): Html
}

/** Default instances for the [[ToHtml]] type class. */
object ToHtml {
  /** Creates an instance of [[ToHtml]] using the provided function. */
  def apply[A](f: A => Html) = new ToHtml[A] {
    def print(value: A): Html = f(value)
  }

  /** Identity function. */
  implicit val htmlToHtml = ToHtml[Html](identity)

  /** Encode string with safe escape of < characters. */
  implicit val stringToHtml = ToHtml[String](s => Html(s.replaceAll("<", "&lt;")))

  /** toString. */
  implicit val intToHtml = ToHtml[Int](i => Html(i.toString))

  /** toString. */
  implicit val longToHtml = ToHtml[Long](l => Html(l.toString))

  /** toString. */
  implicit val floatToHtml = ToHtml[Float](f => Html(f.toString))

  /** toString. */
  implicit val doubleToHtml = ToHtml[Double](d => Html(d.toString))

  /** toString. */
  implicit val booleanToHtml = ToHtml[Boolean](b => Html(b.toString))

  /** toString. */
  implicit val charToHtml = ToHtml[Char](c => Html(c.toString))

  /** Encode the content. */
  implicit def someToHtml[A: ToHtml] = ToHtml[Some[A]](s => implicitly[ToHtml[A]].print(s.get))

  /** Empty. */
  implicit val noneToHtml = ToHtml[None.type](_ => Html.empty)

  /** Encode the content if available or empty. */
  implicit def optionToHtml[A: ToHtml] = ToHtml[Option[A]](_ match {
    case Some(value) => implicitly[ToHtml[A]].print(value)
    case _ => Html.empty
  })

  /** Empty, */
  implicit val unitToHtml = ToHtml[Unit](_ => Html.empty)

  /** Join all elements rendering. */
  implicit def seqToHtml[A: ToHtml] = ToHtml[Seq[A]] { seq =>
    val i = implicitly[ToHtml[A]]
    seq.map(i.print).foldLeft(Html.empty)(_ ++ _)
  }

  /** Join all elements rendering. */
  implicit def listToHtml[A: ToHtml] = ToHtml[List[A]](l => seqToHtml[A].print(l))

  /** Join all elements rendering. */
  implicit def setToHtml[A: ToHtml] = ToHtml[Set[A]](l => seqToHtml[A].print(l.toSeq))

  /** Join all elements rendering. */
  implicit def arrayToHtml[A: ToHtml] = ToHtml[Array[A]](l => seqToHtml[A].print(l))
}

/** An HTML document.
  * @param the actual html text.
  */
case class Html(content: String) {

  /** The html text size. */
  lazy val size: Int = content.size

  /** Merge this Html fragment with another one. */
  def ++(next: Html): Html = Html(content + next.content)
}

/** Provides the [[lol.http.ContentEncoder ContentEncoder]] for HTML documents. */
object Html {

  val empty = Html("")

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