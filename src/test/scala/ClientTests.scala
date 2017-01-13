package lol.http

import Headers._

import scala.util._
import scala.concurrent.{ Future, ExecutionContext }
import scala.concurrent.duration._

import ExecutionContext.Implicits.global

class ClientTests extends Tests {

  test("Client") {
    val data = Map(1 -> "Youhou", 2 -> "Lol", 3 -> "Bam")
    val App: Service = {
      case GET at url"/keys" => {
        Ok(data.keys.mkString(","))
      }
      case GET at url"/data/$key" => {
        Try(key.toInt).map(key =>
          data.get(key).map(Ok(_)).getOrElse(NotFound(s"No data for key: $key"))
        ).getOrElse(
          BadRequest(s"Invalid key format: $key")
        )
      }
      case req => {
        NotFound(s"Endpoint does not exist, ${req.url}")
      }
    }

    withServer(Server.listen()(App)) { server =>
      await {
        Client("localhost", server.port).runAndStop { client =>
          for {
            keys <- client.run(Get("/keys"))(_.read[String])
            oops <- client.run(Get("/blah"))(res => success(res.status))
            _ = oops should be (404)
            results <- Future.sequence(
              keys.split("[,]").toList.map { key =>
                client.run(Get(s"/data/$key"))(_.read[String])
              }
            )
            (status, content) <- client.run(Get("/data/coco"))(res => res.read[String].map(c => (res.status, c)))
            _ = status should be (400)
            _ = content should be ("Invalid key format: coco")
          } yield results
        }
      } should contain theSameElementsInOrderAs data.values
    }
  }

  test("Connection close") {
    withServer(Server.listen() {
      case GET at "/bye" => Ok("See you").addHeaders(CONNECTION -> "Close")
      case GET at "/hello" => Ok("World")
    }) { server =>
      await {
        Client("localhost", server.port).runAndStop { client =>
          for {
            hello <- client.run(Get("/hello"))(_.read[String])
            _ = hello should be ("World")
            hello <- client.run(Get("/hello"))(_.read[String])
            _ = hello should be ("World")
            bye <- client.run(Get("/bye"))(_.read[String])
            _ = bye should be ("See you")
            _ = client.nbConnections should be (0)
            _ <- client.stop()
            _ = an [Exception] should be thrownBy await(client.run(Get("/hello"))())
          } yield ()
        }
      }

      await {
        Client("localhost", server.port).runAndStop { client =>
          for {
            hello <- client.run(Get("/hello"))(_.read[String])
            _ = hello should be ("World")
            hello <- client.run(Get("/hello").addHeaders(CONNECTION -> "Close"))(_.read[String])
            _ = hello should be ("World")
            _ = client.nbConnections should be (0)
            _ <- client.stop()
            _ = an [Exception] should be thrownBy await(client.run(Get("/hello"))())
          } yield ()
        }
      }
    }
  }

  test("Connection leak") {
    withServer(Server.listen() { _ => Ok("World") }) { server =>

      def makeCalls(client: Client, x: Int) = Future.sequence {
        (1 to x).map { i =>
          Future.firstCompletedOf(
            List(
              client(Get("/")).map(_ => "OK"),
              timeout(250 milliseconds, "TIMEOUT")
            )
          ).recover { case _ => "REJECTED" }
        }
      }

      await {
        Client("localhost", server.port, maxConnections = 2, maxWaiters = 10).runAndStop { client =>
          makeCalls(client, 2)
        }
      } should contain theSameElementsAs List("OK", "OK")

      await {
        Client("localhost", server.port, maxConnections = 2, maxWaiters = 10).runAndStop { client =>
          makeCalls(client, 20)
        }
      } should contain theSameElementsAs List(
        "OK", "OK", "TIMEOUT", "TIMEOUT", "TIMEOUT", "TIMEOUT", "TIMEOUT", "TIMEOUT", "TIMEOUT",
        "TIMEOUT", "TIMEOUT", "TIMEOUT", "REJECTED", "REJECTED", "REJECTED", "REJECTED", "REJECTED",
        "REJECTED", "REJECTED", "REJECTED"
      )
    }
  }
}
