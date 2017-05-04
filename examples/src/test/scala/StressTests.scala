package lol.http.examples

import lol.http._
import lol.json._

import io.circe.parser._

import scala.concurrent._
import ExecutionContext.Implicits.global

class StressTests extends Tests {
  val bigJsonDocument = parse(s"""[${1 to 2048 mkString(",")}]""").right.get

  test("Using a new connection for each request", Unsafe) {
    withServer(Server.listen() { _ => Ok(bigJsonDocument) }) { server =>
      (1 to 20).foreach { _ =>
        await() {
          Future.sequence {
            (1 to 64).map { _ =>
              Client("localhost", port = server.port).runAndStop { client =>
                client.run(Get("/"))(_.readAs(json[List[Int]]))
              }
            }
          }
        } should be ((1 to 64).map(_ => (1 to 2048)))
      }
    }
  }

  test("Reusing a connection pool", Unsafe) {
    withServer(Server.listen() { _ => Ok(bigJsonDocument) }) { server =>
      val client = Client("localhost", port = server.port)
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

  test("Reusing a single connection", Unsafe) {
    withServer(Server.listen() { _ => Ok(bigJsonDocument) }) { server =>
      val client = Client("localhost", port = server.port, maxConnections = 1)
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
