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
        addHeaders("X-Lol" -> "ooo").
        addHeaders("X-Foo" -> "Bar", "X-Bam" -> "Bom").
        addHeaders("X-Lol" -> "xxx").
        addHeaders("X-Ohh" -> "Ahh").
        addHeaders(Map("X-Paf" -> "Pif")).
        removeHeader("X-Ohh")
    }) { server =>
      val url = s"http://localhost:${server.port}/"

      contentString(Get(url)) should equal (
        s"Host: localhost:${server.port}"
      )
      contentString(Get(url).addHeaders("User-Agent" -> "lol")) should equal (
        s"Host: localhost:${server.port}, User-Agent: lol"
      )

      val responseHeaders = await() { Client.run(Get(url))(res => success(res.headers)) }

      responseHeaders should contain allOf (
        "X-Lol" -> "xxx",
        "X-Foo" -> "Bar",
        "X-Bam" -> "Bom",
        "X-Paf" -> "Pif"
      )
      responseHeaders.keys should not contain ("X-Ohh")
      responseHeaders should contain allOf (
        "Content-Type" -> "text/plain; charset=UTF-8",
        "Connection" -> "keep-alive"
      )
      responseHeaders.get("Content-Length") should be (Some(
        s"Host: localhost:${server.port}".size.toString
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

      contentString(Request(HttpMethod("Delete"), headers = Map("Host" -> s"localhost:${server.port}"))) should be ("DELETE")
      contentString(Request(HttpMethod("yolo"), headers = Map("Host" -> s"localhost:${server.port}"))) should be ("<-")
      contentString(Request(HttpMethod("Boom"), headers = Map("Host" -> s"localhost:${server.port}"))) should be ("BOOM")
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
