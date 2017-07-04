package lol.http.examples

import lol.http._
import lol.json._

import io.circe.parser._

import scala.concurrent._
import ExecutionContext.Implicits.global

class StressTests extends Tests {
  val bigJsonDocument = parse(s"""[${1 to 2048 mkString(",")}]""").right.get
  val app: Service = _ => Ok(bigJsonDocument)

  test("Using a new connection for each request", Slow) {
    foreachProtocol(HTTP, HTTP2) { protocol =>
      withServer(Server.listen(options = ServerOptions(protocols = Set(protocol)))(app)) { server =>
        (1 to 20).foreach { _ =>
          await() {
            Future.sequence {
              (1 to 64).map { _ =>
                Client("localhost", port = server.port, options = ClientOptions(protocols = Set(protocol))).runAndStop { client =>
                  client.run(Get("/"))(_.readAs(json[List[Int]]))
                }
              }
            }
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
          await() {
            Future.sequence {
              (1 to 64).map { j =>
                client.run(Get(url"/$j"))(res => res.readAs(json[List[Int]]))
              }
            }
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
        (1 to 20).foreach { _ =>
          await() {
            Future.sequence {
              (1 to 64).map { _ =>
                client.run(Get("/"))(_.readAs(json[List[Int]]))
              }
            }
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
          await() {
            client.run(Get(url"/$i"))(_.readAs(json[List[Int]]))
          }
        } should be ((1 to 5000).map(_ => (1 to 2048)))
      }
    }
  }

}
