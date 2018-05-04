package lol

import scala.language.implicitConversions

/** HTML templating.
  *
  * {{{
  * val items: List[Item] = ???
  * val content: Html = tmpl"""
  *   <­h1>Items<­/h1>
  *   @if(items.isEmpty) {
  *     <­em>No results<­/em>
  *   }
  *   else {
  *     <­ul>
  *        @items.map { item =>
  *          &lt;li>@item.name&lt;/li>
  *        }
  *     </ul>
  *   }
  * """
  * }}}
  *
  * [[Html]] values can also be easily created from the [[HtmlInterpolation html]] interpolation.
  * Conversion from Scala values is done via the [[ToHtml]] type class.
  *
  * {{{
  * val content: Html = html"""Hello ${name}!"""
  * }}}
  *
  * They will be encoded as [[lol.http.Content Content]] thanks to [[Html.encoder]].
  */
package object html {

  /** Convert a value to [[Html]] using the right [[ToHtml]] type class instance.
    *
    * If use the provided type class instances, values are encoded this way.:
    *  - [[String]] values are safely HTML escaped to avoid XSS issues with generated documents.
    *  - [[scala.Option]] can be directly inserted. `Some` values content is included, while
        `None` produces an empty string.
    *  - [[scala.Unit]] produces an empty string.
    *  - [[scala.Seq]] outputs every item without any separator.
    */
  implicit def toHtml[A: ToHtml](value: A): Html = implicitly[ToHtml[A]].print(value)

  /** The `html` interpolation allows to create [[Html]] values from plain string. */
  implicit class HtmlInterpolation(val ctx: StringContext) {
    def html(args: Html*) =
      ctx.parts.map(Html.apply).zipAll(args, Html.empty, Html.empty).map(x => x._1 ++ x._2).foldLeft(Html.empty)(_ ++ _)
  }

  /** The `tmpl` interpolation allows to create [[Html]] values from a string template.
    *
    * The template syntax is almost the same as Twirl (https://www.playframework.com/documentation/2.6.x/ScalaTemplates).
    * It is often more convenient that using imbricated string interpolation.
    */
  implicit class TemplateInterpolation(val ctx: StringContext) {
    def tmpl(args: Any*): Html = macro Template.macroImpl
  }

  /** Extension methods for [[Seq[Html]]]. */
  implicit class SeqHtmlExtensions(val seq: Seq[Html]) {
    def mkHtml[B: ToHtml](separator: B) = {
      val separatorHtml = implicitly[ToHtml[B]].print(separator)
      if(seq.isEmpty)
        Html.empty
      else if(seq.size == 1)
        seq(0)
      else
        seq.tail.foldLeft(seq.head)(_ ++ separatorHtml ++ _)
    }
  }

  /** Extension methods for [[Seq[_]]]. */
  implicit class SeqHtmlExtensions0[A](val seq: Seq[A]) {
    def join[B: ToHtml](separator: B)(f: A => Html) =
      seq.map(f).mkHtml(separator)
  }
}
