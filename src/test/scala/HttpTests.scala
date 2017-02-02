package lol.http

import java.util.logging.{Level, Logger}

import scala.concurrent.ExecutionContext
import ExecutionContext.Implicits.global

class HttpTests extends Tests {

  Logger.getLogger("io.netty.channel.DefaultChannelPipeline").setLevel(Level.FINE)

  test("Hello World") {
    withServer(Server.listen() { _ => Ok("Hello World") }) { server =>
      val urls = List(
        s"http://localhost:${server.port}",
        s"http://localhost:${server.port}/",
        s"http://localhost:${server.port}/lol",
        s"http://localhost:${server.port}/lol?foo=bar"
      )

      urls.foreach { url => status(Get(url)) should equal (200) }
      urls.foreach { url => contentString(Get(url)) should equal ("Hello World") }
    }
  }

  test("Headers") {
    withServer(Server.listen() { req =>
      val requestHeaders = req.headers.toSeq.sortBy(_._1).map { case (k,v) => s"$k: $v"}.mkString(", ")
      Ok(requestHeaders).
        addHeaders(h"X-Lol" -> h"ooo").
        addHeaders(h"X-Foo" -> h"Bar", h"X-Bam" -> h"Bom").
        addHeaders(h"X-Lol" -> h"xxx").
        addHeaders(h"X-Ohh" -> h"Ahh").
        addHeaders(Map(h"X-Paf" -> h"Pif")).
        removeHeader(h"X-Ohh")
    }) { server =>
      val url = s"http://localhost:${server.port}/"

      contentString(Get(url)) should equal (
        s"Host: localhost:${server.port}"
      )
      contentString(Get(url).addHeaders(h"User-Agent" -> h"lol")) should equal (
        s"Host: localhost:${server.port}, User-Agent: lol"
      )

      val responseHeaders = await() { Client.run(Get(url))(res => success(res.headers)) }

      responseHeaders should contain allOf (
        h"X-Lol" -> h"xxx",
        h"X-Foo" -> h"Bar",
        h"X-Bam" -> h"Bom",
        h"X-Paf" -> h"Pif"
      )
      responseHeaders should contain allOf (
        h"x-LOL" -> h"XxX",
        h"X-FOO" -> h"BAR",
        h"X-Bam" -> h"bom",
        h"x-paf" -> h"Pif"
      )
      responseHeaders.keys should not contain (h"X-Ohh")
      responseHeaders should contain allOf (
        h"Content-Type" -> h"text/plain; charset=UTF-8",
        h"Connection" -> h"keep-alive"
      )
      responseHeaders.get(h"Content-Length") should be (Some(
        h"Host: localhost:${server.port}".toString.size.toString
      ))
    }
  }

  test("Methods") {
    withServer(Server.listen() {
      case GET at _ => Ok("Hello")
      case (PUT | POST) at _ => Ok("Hoho")
      case HttpMethod("YOLO") at _ => Ok("<-")
      case req => Ok(req.method.toString)
    }) { server =>
      val url = s"http://localhost:${server.port}/"

      contentString(Get(url)) should be ("Hello")
      contentString(Post(url, ())) should be ("Hoho")
      contentString(Put(url, ())) should be ("Hoho")
      contentString(Delete(url)) should be ("DELETE")

      // expected because HEAD ignore the body
      contentString(Head(url)) should be ("")

      contentString(Request(HttpMethod("Delete"), headers = Map(h"Host" -> h"localhost:${server.port}"))) should be ("DELETE")
      contentString(Request(HttpMethod("yolo"), headers = Map(h"Host" -> h"localhost:${server.port}"))) should be ("<-")
      contentString(Request(HttpMethod("Boom"), headers = Map(h"Host" -> h"localhost:${server.port}"))) should be ("BOOM")
    }
  }

  test("Redirects") {
    withServer(Server.listen() {
      case GET at "/" => Redirect("/lol")
      case GET at "/old" => Redirect("/", permanent = true)
      case _ at "/lol" => Ok("lol")
      case _ => NotFound
    }) { server =>
      val url = s"http://localhost:${server.port}"

      status(Get(s"$url/")) should be (307)
      status(Get(s"$url/old")) should be (308)
      status(Get(s"$url/lol")) should be (200)
      status(Get(s"$url/wat")) should be (404)

      await() { Client.run(Get(s"$url/"), followRedirects = true)(_.read[String]) } should be ("lol")
      await() { Client.run(Get(s"$url/old"), followRedirects = true)(_.read[String]) } should be ("lol")
    }
  }

}
