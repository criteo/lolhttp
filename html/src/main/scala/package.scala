package lol

/** HTML templating.
  *
  * {{{
  * val items: List[Item] = ???
  * val content: Html = html"""
  *   <­h1>Items<­/h1>
  *   \${if(items.isEmpty) {
  *     html"<­em>No results<­/em>"
  *   } else {
  *     html"""
  *       <­ul>
  *         \${items.map { item =>
  *           html"<­li>\${item.name}<­/li>"
  *         }}
  *       <­/ul>
  *     """
  *   }}
  * """
  * }}}
  *
  * [[Html]] values can be easily created from the [[HtmlInterpolation html]] interpolation.
  * They will be encoded as [[lol.http.Content Content]] thanks to [[Html.encoder]].
  */
package object html {

  /** The `html` interpolation allows to create [[Html]] values from plain string. It is very close to the standard `s`
    * interpolation with a few differences:
    *
    *  - Every bit of text dynamically injected is safely HTML escaped to avoid XSS issues with generated documents.
    *  - Including another [[Html]] value disables this escaping. The raw html text is then included as is.
    *  - [[scala.Option]] can be directly inserted. `Some` values content is included, while 
         `None` produces an empty string.
    *  - [[scala.Unit]] produces an empty string.
    *  - [[scala.TraversableOnce]] outputs every item without any separator.
    */
  implicit class HtmlInterpolation(val ctx: StringContext) {
    private def serialize(arg: Any): String = arg match {
      case Html(x) => x
      case Some(x) => serialize(x)
      case None => ""
      case () => ""
      case x: TraversableOnce[_] => x.map(serialize).mkString
      case x => x.toString.replaceAll("<", "&lt;")
    }
    def html(args: Any*) = Html(ctx.s(args.map(serialize): _*))
  }
}
