package lol.http.examples

import cats.effect._

import lol.http._
import lol.json._

import io.circe.parser._

import cats.implicits._

import scala.concurrent.ExecutionContext

class StressTests extends Tests {
  val bigJsonDocument = parse(s"""[${1 to 2048 mkString(",")}]""").right.get
  val app: Service = req => Ok(bigJsonDocument).addHeaders(h"X-RESPONSE-FOR" -> HttpString(req.path))

  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
  implicit val timer: Timer[IO] = IO.timer(ec)
  implicit val cs: ContextShift[IO] = IO.contextShift(ec)

  test("Using a new connection for each request", Slow) {
    withServer(Server.listen()(app)) { server =>
      (1 to 20).foreach { i =>
        println(s"- $i")
        await() {
          (1 to 64).map { _ =>
            Client("localhost", port = server.port).runAndStop { client =>
              client.run(Get("/"))(_.readAs(json[List[Int]]))
            }
          }.toList.parSequence
        } should be ((1 to 64).map(_ => (1 to 2048)))
      }
    }
  }

  test("Reusing a connection pool", Slow) {
    withServer(Server.listen()(app)) { server =>
      val client = Client("localhost", port = server.port, maxConnections = 1)
      (1 to 20).foreach { i =>
        println(s"- $i")
        await() {
          (1 to 64).map { j =>
            client.run(Get(url"/$j"))(res => res.readAs(json[List[Int]]))
          }.toList.parSequence
        } should be ((1 to 64).map(_ => (1 to 2048)))
      }
      client.stop()
    }
  }

  test("Reusing a single connection", Slow) {
    withServer(Server.listen()(app)) { server =>
      val client = Client("localhost", port = server.port, maxConnections = 1)
      (1 to 20).foreach { i =>
        println(s"- $i")
        await() {
          (1 to 64).map { _ =>
            client.run(Get("/"))(_.readAs(json[List[Int]]))
          }.toList.parSequence
        } should be ((1 to 64).map(_ => (1 to 2048)))
      }
      client.stop()
    }
  }

  test("Sequential, using a new connection for each request", Slow) {
    withServer(Server.listen()(app)) { server =>
      (1 to 5000).map { i =>
        if(i % 100 == 0) println(s"- $i")
        await() {
          Client("localhost", port = server.port).runAndStop { client =>
            client.run(Get(url"/$i"))(_.readAs(json[List[Int]]))
          }
        }
      } should be ((1 to 5000).map(_ => (1 to 2048)))
    }
  }

  test("Sequential, using a connection pool", Slow) {
    withServer(Server.listen()(app)) { server =>
      val client = Client("localhost", port = server.port, maxConnections = 1)
      (1 to 5000).map { i =>
        if(i % 100 == 0) println(s"- $i")
        await() {
          client.run(Get(url"/$i"))(res => res.readAs(json[List[Int]]))
        }
      } should be ((1 to 5000).map(_ => (1 to 2048)))
    }
  }

}
