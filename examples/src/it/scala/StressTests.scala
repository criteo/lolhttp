package lol.http.examples

import lol.http._
import lol.json._

import io.circe.parser._

import cats.implicits._

import scala.concurrent.ExecutionContext.Implicits.global

class StressTests extends Tests {
  val bigJsonDocument = parse(s"""[${1 to 2048 mkString(",")}]""").right.get
  val app: Service = req => Ok(bigJsonDocument).addHeaders(h"X-RESPONSE-FOR" -> HttpString(req.path))

  test("Using a new connection for each request", Slow) {
    foreachProtocol(HTTP, HTTP2) { protocol =>
      withServer(Server.listen(options = ServerOptions(protocols = Set(protocol)))(app)) { server =>
        (1 to 20).foreach { i =>
          println(s"# $protocol - $i")
          await() {
            fs2.async.parallelSequence((1 to 64).map { _ =>
              Client("localhost", port = server.port, options = ClientOptions(protocols = Set(protocol))).runAndStop { client =>
                client.run(Get("/"))(_.readAs(json[List[Int]]))
              }
            }.toList)
          } should be ((1 to 64).map(_ => (1 to 2048)))
        }
      }
    }
  }

  test("Reusing a connection pool", Slow) {
    foreachProtocol(HTTP, HTTP2) { protocol =>
      withServer(Server.listen(options = ServerOptions(protocols = Set(protocol)))(app)) { server =>
        val client = Client("localhost", port = server.port, maxConnections = 1, options = ClientOptions(protocols = Set(protocol)))
        (1 to 20).foreach { i =>
          println(s"# $protocol - $i")
          await() {
            fs2.async.parallelSequence((1 to 64).map { j =>
              client.run(Get(url"/$j"))(res => res.readAs(json[List[Int]]))
            }.toList)
          } should be ((1 to 64).map(_ => (1 to 2048)))
        }
        client.stop()
      }
    }
  }

  test("Reusing a single connection", Slow) {
    foreachProtocol(HTTP, HTTP2) { protocol =>
      withServer(Server.listen(options = ServerOptions(protocols = Set(protocol)))(app)) { server =>
        val client = Client("localhost", port = server.port, maxConnections = 1, options = ClientOptions(protocols = Set(protocol)))
        (1 to 20).foreach { i =>
          println(s"# $protocol - $i")
          await() {
            fs2.async.parallelSequence((1 to 64).map { _ =>
              client.run(Get("/"))(_.readAs(json[List[Int]]))
            }.toList)
          } should be ((1 to 64).map(_ => (1 to 2048)))
        }
        client.stop()
      }
    }
  }

  test("Sequential, using a new connection for each request", Slow) {
    foreachProtocol(HTTP, HTTP2) { protocol =>
      withServer(Server.listen(options = ServerOptions(protocols = Set(protocol)))(app)) { server =>
        (1 to 5000).map { i =>
          if(i % 100 == 0) println(s"# $protocol - $i")
          await() {
            Client("localhost", port = server.port, options = ClientOptions(protocols = Set(protocol))).runAndStop { client =>
              client.run(Get(url"/$i"))(_.readAs(json[List[Int]]))
            }
          }
        } should be ((1 to 5000).map(_ => (1 to 2048)))
      }
    }
  }

  test("Sequential, using a connection pool", Slow) {
    foreachProtocol(HTTP, HTTP2) { protocol =>
      withServer(Server.listen(options = ServerOptions(protocols = Set(protocol)))(app)) { server =>
        val client = Client("localhost", port = server.port, maxConnections = 1, options = ClientOptions(protocols = Set(protocol)))
        (1 to 5000).map { i =>
          if(i % 100 == 0) println(s"# $protocol - $i")
          await() {
            client.run(Get(url"/$i"))(res => res.readAs(json[List[Int]]))
          }
        } should be ((1 to 5000).map(_ => (1 to 2048)))
      }
    }
  }

}
