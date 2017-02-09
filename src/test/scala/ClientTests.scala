package lol.http

import fs2.{ Task, Chunk, Stream }

import java.util.concurrent.{ TimeoutException }

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
      await() {
        Client("localhost", server.port).runAndStop { client =>
          for {
            keys <- client.run(Get("/keys"))(_.read[String])
            _ = keys should be ("1,2,3")
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

  test("Large content", Slow) {
    val App: Service = {
      case GET at url"/huge" => {
        Ok(
          Content(
            Stream.eval(Task.delay(Chunk.bytes(("A" * 1024).getBytes("us-ascii")))).
              repeat.
              take(1024).
              flatMap(Stream.chunk),
            Map(Headers.ContentLength -> HttpString(1024 * 1024))
          )
        )
      }
    }

    withServer(Server.listen()(App)) { server =>
      await() {
        Client("localhost", server.port).runAndStop { client =>
          for {
            response <- client(Get("/huge"))
            _ = response.status should be (200)
            length <- response.content.stream.chunks.runFold(0: Long)(_ + _.size).unsafeRunAsyncFuture()
          } yield length
        }
      } should be (1024 * 1024)
    }
  }

  test("Connection close") {
    withServer(Server.listen() {
      case GET at "/bye" => Ok("See you").addHeaders(Headers.Connection -> h"Close")
      case GET at "/hello" => Ok("World")
    }) { server =>
      await() {
        Client("localhost", server.port).runAndStop { client =>
          for {
            bye <- client.run(Get("/bye"))(_.read[String])
            _ = bye should be ("See you")
            _ = eventually(client.nbConnections should be (0))
            hello <- client.run(Get("/hello").addHeaders(Headers.Connection -> h"CLOSE"))(_.read[String])
            _ = hello should be ("World")
            _ = eventually(client.nbConnections should be (0))
            _ <- client.stop()
            _ = the [Error] thrownBy await() { client.run(Get("/hello"))() } shouldBe (Error.ClientAlreadyClosed)
          } yield ()
        }
      }

      await() {
        Client("localhost", server.port).runAndStop { client =>
          for {
            hello <- client.run(Get("/hello").addHeaders(Headers.Connection -> h"Close"))(_.read[String])
            _ = hello should be ("World")
            _ = eventually(client.nbConnections should be (0))
            _ <- client.stop()
            _ = the [Error] thrownBy await() { client.run(Get("/hello"))() } shouldBe (Error.ClientAlreadyClosed)
          } yield ()
        }
      }
    }
  }

  test("Connection leak") {
    withServer(Server.listen() { _ => Ok("World" * 1024 * 100) }) { server =>

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

      await() {
        Client("localhost", server.port, maxConnections = 2, maxWaiters = 10).runAndStop { client =>
          makeCalls(client, 2)
        }
      } should contain theSameElementsAs List("OK", "OK")

      await() {
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

  test("Single connection", Slow) {
    withServer(Server.listen() { case GET at url"/$word" =>
      Ok(Content(
        Stream.chunk(Chunk.bytes((word * 1024 * 100).getBytes("us-ascii"))),
        Map(Headers.ContentLength -> HttpString(1024 * 100 * word.size))
      ))
    }) { server =>

      the [Error] thrownBy await() {
        Client("localhost", server.port, maxConnections = 1).runAndStop { client =>
          for {
            response <- client(Get("/Hello"))
            _ = response.status should be (200)
            helloBytes <- response.content.stream.take(8).runLog.unsafeRunAsyncFuture()
            _ = new String(helloBytes.toArray, "us-ascii") should be ("HelloHel")

            // illegal to reopen the stream
            _ <- response.content.stream.runLog.unsafeRunAsyncFuture()
          } yield ()
        }
      } should be (Error.StreamAlreadyConsumed)

      a [TimeoutException] should be thrownBy await(2 seconds) {
        Client("localhost", server.port, maxConnections = 1).runAndStop { client =>
          for {
            response <- client(Get("/Hello"))
            _ = response.status should be (200)

            // we forgot to consume the stream, so the connection is not ready for
            // the next request
            response2 <- client(Get("/lol"))
          } yield ()
        }
      }

    }
  }
}
