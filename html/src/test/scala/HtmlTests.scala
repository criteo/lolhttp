package lol.html

import lol.http._

class HtmlTests extends   Tests {

  test("HTML encoding") {
    val someHtml = html"<h1>Hello world!</h1>"
    val htmlContent = Content.of(someHtml)
    htmlContent.headers.get(Headers.ContentType) should be (Some(h"text/html; charset=UTF-8"))
    htmlContent.headers.get(Headers.ContentLength) should be (Some(HttpString(someHtml.size)))
  }

  test("HTML auto escaping") {
    val safeName = "world"
    val unsafeName = "<script>alert('héhé')</script>"
    (html"<h1>Hello $safeName!</h1>").content should be ("<h1>Hello world!</h1>")
    (html"<h1>Hello $unsafeName!</h1>").content should be ("<h1>Hello &lt;script>alert('héhé')&lt;/script>!</h1>")
  }

  test("HTML safe include") {
    val safeName = "<strong>world</strong>"
    (html"<h1>Hello $safeName!</h1>").content should be ("<h1>Hello &lt;strong>world&lt;/strong>!</h1>")
    (html"<h1>Hello ${Html(safeName)}!</h1>").content should be ("<h1>Hello <strong>world</strong>!</h1>")
  }

  test("HTML option rendering") {
    html"<h1>Hello ${Some("world")}!</h1>".content should be ("<h1>Hello world!</h1>")
    html"<h1>Hello ${None}!</h1>".content should be ("<h1>Hello !</h1>")
    html"<h1>Hello ${None.getOrElse("guest")}!</h1>".content should be ("<h1>Hello guest!</h1>")
  }

  test("HTML seq rendering") {
    val items = Seq(1,2,3)
    html"<ul>${items.map(i => html"<li>$i.</li>")}</ul>".content should be ("<ul><li>1.</li><li>2.</li><li>3.</li></ul>")
  }
}
