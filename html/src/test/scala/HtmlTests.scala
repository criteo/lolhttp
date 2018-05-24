package lol.html

import lol.http._

class HtmlTests extends Tests {

  test("ToHtml typeclass") {
    toHtml("lol") should be (Html("lol"))
    toHtml("<lol>") should be (Html("&lt;lol>"))
    toHtml(None) should be (Html.empty)
    toHtml(Some("lol")) should be (Html("lol"))
    toHtml(Some("<lol>")) should be (Html("&lt;lol>"))
    toHtml(()) should be (Html.empty)
    toHtml(2) should be (Html("2"))
    toHtml(2.5) should be (Html("2.5"))
    toHtml(false) should be (Html("false"))
    toHtml(Html("lol")) should be (Html("lol"))
    toHtml(Html("<lol>")) should be (Html("<lol>"))
    toHtml(List(1,2,3)) should be (Html("123"))
    """toHtml(new java.net.URI("http://criteo.com"))""" shouldNot compile

    implicit val noEncoding = ToHtml[String](s => Html(s))
    toHtml("lol") should be (Html("lol"))
    toHtml("<lol>") should be (Html("<lol>"))
    toHtml(Some("lol")) should be (Html("lol"))
    toHtml(Some("<lol>")) should be (Html("<lol>"))

    implicit val uriToHtml = ToHtml[java.net.URI](u => Html(u.toString))
    toHtml(new java.net.URI("http://criteo.com")) should be (Html("http://criteo.com"))
  }

  test("HTML encoding") {
    val someHtml = html"<h1>Hello world!</h1>"
    val htmlContent = Content.of(someHtml)
    htmlContent.headers.get(Headers.ContentType) should be (Some(h"text/html; charset=UTF-8"))
    htmlContent.headers.get(Headers.ContentLength) should be (Some(HttpString(someHtml.size)))
  }

  test("HTML interpolation escaping") {
    val safeName = "world"
    val unsafeName = "<script>alert('héhé')</script>"
    (html"<h1>Hello $safeName!</h1>").content should be ("<h1>Hello world!</h1>")
    (html"<h1>Hello $unsafeName!</h1>").content should be ("<h1>Hello &lt;script>alert('héhé')&lt;/script>!</h1>")
  }

  test("HTML interpolation safe include") {
    val safeName = "<strong>world</strong>"
    (html"<h1>Hello $safeName!</h1>").content should be ("<h1>Hello &lt;strong>world&lt;/strong>!</h1>")
    (html"<h1>Hello ${Html(safeName)}!</h1>").content should be ("<h1>Hello <strong>world</strong>!</h1>")
  }

  test("HTML interpolation option rendering") {
    html"<h1>Hello ${Some("world")}!</h1>".content should be ("<h1>Hello world!</h1>")
    html"<h1>Hello ${None}!</h1>".content should be ("<h1>Hello !</h1>")
    html"<h1>Hello ${None.getOrElse("guest")}!</h1>".content should be ("<h1>Hello guest!</h1>")
  }

  test("HTML interpolation seq rendering") {
    val items = Seq(1,2,3)
    html"<ul>${items.map(i => html"<li>$i.</li>")}</ul>".content should be ("<ul><li>1.</li><li>2.</li><li>3.</li></ul>")
  }

  test("HTML extensions") {
    Nil.mkHtml(html",").content should be ("")
    List(html"1").mkHtml(",").content should be ("1")
    List(html"1", html"2", html"3").mkHtml(",").content should be ("1,2,3")
    (List(1,2,3).join(";") { i => toHtml(i) }).content should be ("1;2;3")
  }

  test("Template") {
    case class Item(id: String, name: String)
    val items = List(Item("lol", "Lol"), Item("kiki", "KIKI"))

    tmpl"<h1>@items.size</h1>".content should be ("<h1>2</h1>")
    tmpl"<h1>@(items.size)!</h1>".content should be ("<h1>2!</h1>")
    tmpl"<h1>@{items.size}!</h1>".content should be ("<h1>2!</h1>")
    tmpl"<h1>{ @items.size }</h1>".content should be ("<h1>{ 2 }</h1>")
    tmpl"<h1>@items.size!</h1>".content should be ("<h1>2!</h1>")
    tmpl"<h1>@if(items.nonEmpty) { @items.size items } else { no items }</h1>".content should be ("<h1>2 items</h1>")
    tmpl"<h1>@if(false) { LOL }</h1>".content should be ("<h1></h1>")
    tmpl"<h1>@if(false) { LOL } else if(false) { LOL }</h1>".content should be ("<h1></h1>")
    tmpl"<h1>@if(false) { LOL } else if(false) { LOL } else { wat? }</h1>".content should be ("<h1>wat?</h1>")
    tmpl"<ul>@for(i <- 0 to 1) { <li>@i</li> }</ul>".content should be ("<ul></ul>")
    tmpl"<ul>@for(i <- 0 to 1) yield { <li>@i</li> }</ul>".content should be ("<ul><li>0</li><li>1</li></ul>")
    tmpl"""
      <p>@items.lift(0) match {
        case Some(Item(id, name)) => <em>@name</em>
        case None => <em>Empty</em>
      }</p>
    """.content.trim should be ("<p><em>Lol</em></p>")
    tmpl"""
      <p>@items.lift(0) match {
        case Some(Item(id, name)) => { <em>@name</em> }
        case None => { <em>Empty</em> }
      }</p>
    """.content.trim should be ("<p>{ <em>Lol</em> }</p>")
  }
}
