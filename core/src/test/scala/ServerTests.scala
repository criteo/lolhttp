package lol.http

import cats.effect.IO
import fs2.{ Stream, Chunk }

import scala.concurrent.{ ExecutionContext }
import ExecutionContext.Implicits.global

class ServerTests extends Tests {

  test("Hello World") {
    foreachProtocol(HTTP, HTTP2) { protocol =>
      withServer(Server.listen(options = ServerOptions(protocols = Set(protocol))) { _ => Ok("Hello World") }) { server =>
        val urls = List(
          s"http://localhost:${server.port}",
          s"http://localhost:${server.port}/",
          s"http://localhost:${server.port}/lol",
          s"http://localhost:${server.port}/lol?foo=bar"
        )

        urls.foreach { url => status(Get(url), protocol = protocol) should equal (200) }
        urls.foreach { url => contentString(Get(url), protocol = protocol) should equal ("Hello World") }
      }
    }
  }

  test("Protocols") {
    val app: Service = req => Ok(s"Protocol: ${req.protocol}")
    foreachProtocol(HTTP, HTTP2) { protocol =>
      withServer(Server.listen(options = ServerOptions(protocols = Set(protocol)))(app)) { server =>
        contentString(Get(s"http://localhost:${server.port}"), protocol = protocol) should equal (s"Protocol: ${protocol}")
      }
    }

    // On clear connection, the client will choose HTTP/1.1 without prior knowledge
    withServer(Server.listen(options = ServerOptions(protocols = Set(HTTP, HTTP2)))(app)) { server =>
      contentString(Get(s"http://localhost:${server.port}")) should equal (s"Protocol: HTTP/1.1")
    }

    // Force client prior knowledge to use HTTP/2
    withServer(Server.listen(options = ServerOptions(protocols = Set(HTTP, HTTP2)))(app)) { server =>
      contentString(Get(s"http://localhost:${server.port}"), protocol = HTTP2) should equal (s"Protocol: HTTP/2")
    }
  }

  test("Headers") {
    foreachProtocol(HTTP, HTTP2) { protocol =>
      withServer(Server.listen(options = ServerOptions(protocols = Set(protocol))) { req =>
        val requestHeaders = req.headers.toSeq.sortBy(_._1).map { case (k,v) => s"$k: $v".toLowerCase}.mkString("|")
        Ok(requestHeaders).
          addHeaders(h"X-Lol" -> h"ooo").
          addHeaders(h"X-Foo" -> h"Bar", h"X-Bam" -> h"Bom").
          addHeaders(h"X-Lol" -> h"xxx").
          addHeaders(h"X-Ohh" -> h"Ahh").
          addHeaders(Map(h"X-Paf" -> h"Pif")).
          removeHeaders(h"X-Ohh")
      }) { server =>
        val url = s"http://localhost:${server.port}/"

        contentString(Get(url), protocol = protocol).split("[|]") should contain allOf (
          s"host: localhost:${server.port}",
          "content-length: 0"
        )
        contentString(Get(url).addHeaders(h"User-Agent" -> h"lol"), protocol = protocol).split("[|]") should contain allOf (
          s"host: localhost:${server.port}",
          "content-length: 0",
          "user-agent: lol"
        )

        val responseHeaders = await() { Client.run(Get(url), options = ClientOptions(protocols = Set(protocol)))(res => success(res.headers)) }

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
        responseHeaders should contain (h"Content-Type" -> h"text/plain; charset=UTF-8")
        responseHeaders.get(h"Content-Length") should be (Some(
          h"host: localhost:${server.port}|content-length: 0".toString.size.toString
        ))
      }
    }
  }

  test("Methods") {
    foreachProtocol(HTTP, HTTP2) { protocol =>
      withServer(Server.listen(options = ServerOptions(protocols = Set(protocol))) {
        case GET at _ => Ok("Hello")
        case (PUT | POST) at _ => Ok("Hoho")
        case HttpMethod("YOLO") at _ => Ok("<-")
        case req => Ok(req.method.toString)
      }) { server =>
        val url = s"http://localhost:${server.port}/"

        contentString(Get(url), protocol = protocol) should be ("Hello")
        contentString(Post(url, ()), protocol = protocol) should be ("Hoho")
        contentString(Put(url, ()), protocol = protocol) should be ("Hoho")
        contentString(Delete(url), protocol = protocol) should be ("DELETE")
        contentString(Head(url), protocol = protocol) should be ("")

        contentString(Request(HttpMethod("Delete"), headers = Map(h"Host" -> h"localhost:${server.port}")), protocol = protocol) should be ("DELETE")
        contentString(Request(HttpMethod("yolo"), headers = Map(h"Host" -> h"localhost:${server.port}")), protocol = protocol) should be ("<-")
        contentString(Request(HttpMethod("Boom"), headers = Map(h"Host" -> h"localhost:${server.port}")), protocol = protocol) should be ("BOOM")
      }
    }
  }

  test("Redirects") {
    foreachProtocol(HTTP, HTTP2) { protocol =>
      withServer(Server.listen(options = ServerOptions(protocols = Set(protocol))) {
        case GET at "/" => Redirect("/lol")
        case GET at "/old" => Redirect("/", permanent = true)
        case _ at "/lol" => Ok("lol")
        case _ => NotFound
      }) { server =>
        val url = s"http://localhost:${server.port}"

        status(Get(s"$url/"), followRedirects = false, protocol = protocol) should be (307)
        status(Get(s"$url/old"), followRedirects = false, protocol = protocol) should be (308)
        status(Get(s"$url/lol"), followRedirects = false, protocol = protocol) should be (200)
        status(Get(s"$url/wat"), followRedirects = false, protocol = protocol) should be (404)

        contentString(Get(s"$url/"), followRedirects = false, protocol = protocol) should be ("")
        contentString(Get(s"$url/lol"), followRedirects = false, protocol = protocol) should be ("lol")

        contentString(Get(s"$url/"), followRedirects = true, protocol = protocol) should be ("lol")
        contentString(Get(s"$url/lol"), followRedirects = true, protocol = protocol) should be ("lol")
      }
    }
  }

  test("Upload", Slow) {
    foreachProtocol(HTTP, HTTP2) { protocol =>
      withServer(Server.listen(options = ServerOptions(protocols = Set(protocol))) {
        case req @ POST at url"/" =>
          req.read(_.chunks.runFold(0)((size,chunk) => size + chunk.size)).map { contentSize =>
            Ok(s"Received $contentSize bytes")
          }
        case req @ POST at url"/take/$size" =>
          req.read(_.take(size.toInt).chunks.runFold(0)((size,chunk) => size + chunk.size)).map { contentSize =>
            Ok(s"Took $contentSize bytes")
          }
      }) { server =>
        def oneMeg = Content(
          Stream.eval(IO(Chunk.bytes(("A" * 1024).getBytes("us-ascii")))).
            repeat.
            take(1024).
            flatMap(chunk => Stream.chunk(chunk)),
          Map(Headers.ContentLength -> HttpString(1024 * 1024))
        )

        await() {
          Client("localhost", server.port, maxConnections = 1, options = ClientOptions(protocols = Set(protocol))).runAndStop { client =>
            for {
              a <- client.run(Post("/", oneMeg))(_.readAs[String])
              _ = a should be ("Received 1048576 bytes")

              b <- client.run(Post("/take/10", oneMeg))(_.readAs[String])
              _ = b should be ("Took 10 bytes")

              c <- client.run(Post("/take/2048", oneMeg))(_.readAs[String])
              _ = c should be ("Took 2048 bytes")

              d <- client.run(Post("/", oneMeg))(_.readAs[String])
              _ = d should be ("Received 1048576 bytes")

            } yield ()
          }
        }
      }
    }
  }

  test("No Content-Length") {
    foreachProtocol(HTTP, HTTP2) { protocol =>
      withServer(Server.listen(options = ServerOptions(protocols = Set(protocol))) { _ =>
        Ok(Content(stream = Stream.chunk(Chunk.bytes("LOL".getBytes("utf-8")))))
      }) { server =>
        await() {
          Client("localhost", server.port, options = ClientOptions(protocols = Set(protocol))).runAndStop { client =>
            for {
              lol <- client.run(Get("/"))(_.readAs[String])
              _ = lol should be ("LOL")
              _ = eventually(client.openedConnections should be (protocol match {
                case `HTTP` => 0
                case `HTTP2` => 1
              }))
            } yield ()
          }
        }
      }
    }
  }

}
