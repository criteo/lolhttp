package lol.http

import cats.effect.{ContextShift, IO, Timer}
import cats.implicits._
import fs2.{Chunk, Stream}

import scala.util._
import scala.concurrent.duration._
import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContext

class ClientTests extends Tests {

  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
  implicit val timer: Timer[IO] = IO.timer(ec)
  implicit val cs: ContextShift[IO] = IO.contextShift(ec)

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
        ): Response
      }
      case req => {
        NotFound(s"Endpoint does not exist, ${req.url}")
      }
    }

    withServer(Server.listen()(App)) { server =>
      await() {
        Client("localhost", server.port).runAndStop { client =>
          for {
            keys <- client.run(Get("/keys"))(_.readAs[String])
            _ = keys should be ("1,2,3")
            oops <- client.run(Get("/blah"))(res => IO.pure(res.status))
            _ = oops should be (404)
            results <-
              keys.split("[,]").toList.map { key =>
                client.run(Get(s"/data/$key"))(_.readAs[String])
              }.sequence
            x <- client.run(Get("/data/coco"))(res => res.readAs[String].map(c => (res.status, c)))
            _ = x._1 should be (400)
            _ = x._2 should be ("Invalid key format: coco")
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
            Stream.eval(IO(Chunk.bytes(("A" * 1024).getBytes("us-ascii")))).
              repeat.
              take(1024).
              flatMap(chunk => Stream.chunk(chunk)),
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
            length <- response.content.stream.chunks.compile.fold(0: Long)(_ + _.size)
          } yield length
        }
      } should be (1024 * 1024)
    }
  }

  test("Client close") {
    withServer(Server.listen() { case GET at "/hello" => Ok("World") }) { server =>
      await() {
        Client("localhost", server.port).runAndStop { client =>
          for {
            hello <- client.run(Get("/hello"))(_.readAs[String])
            _ = hello should be ("World")
            _ <- client.stop()
            _ = the [Error] thrownBy await() { client.run(Get("/hello"))() } shouldBe (Error.ClientAlreadyClosed)
          } yield ()
        }
      }
    }
  }

  test("Connection leak") {
    withServer(Server.listen() { _ => Ok("World" * 1024 * 100) }) { server =>

      def makeCalls(client: Client, x: Int) =
        (1 to x).map { i =>
          client(Get("/"), timeout = 1.second).map(_ => "OK").recover { case _ => "REJECTED"}
        }.toList.parSequence

      await() {
        Client("localhost", server.port, maxConnections = 2).runAndStop { client =>
          makeCalls(client, 2)
        }
      } should contain theSameElementsAs (0 until 2).map(_ => "OK")

      await() {
        Client("localhost", server.port, maxConnections = 2).runAndStop { client =>
          makeCalls(client, 20)
        }
      } should contain theSameElementsAs ((0 until 2).map(_ => "OK") ++ (0 until 18).map(_ => "REJECTED"))
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
            helloBytes <- response.content.stream.take(8).compile.toVector
            _ = new String(helloBytes.toArray, "us-ascii") should be ("HelloHel")

            // illegal to reopen the stream
            _ <- response.content.stream.compile.toVector
          } yield ()
        }
      } should be (Error.StreamAlreadyConsumed)

      a [TimeoutException] should be thrownBy await(2.seconds) {
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

  test("Timeouts", Slow) {
    val app: Service = _ => IO.sleep(5.seconds) >> IO(Ok)
    withServer(Server.listen()(app)) { server =>
      val client = Client("localhost", server.port)

      try {
        val thrown = the [TimeoutException] thrownBy await() {
          client.run(Get("/"), timeout = 1.second)(res => IO.pure(res.status))
        }
        thrown.getMessage should equal ("1 second")
      }
      finally {
        client.stopSync()
      }

    }
  }

  test("Connection errors", Slow) {
    val requests = 16
    val client = Client("doesnotexist")
    def send(id: Int) = {
      val req = Get("/")
      client.run(req) { _ =>
        IO.pure(1)
      } recoverWith {
        case t: Throwable =>
          IO.pure(0)
      }
    }

    try {
      await(5.seconds) { send(1) } should be (0)

      await(5.seconds) { (1 to requests).map(send).toList.sequence }.sum should be (0)
      eventually(client.openedConnections should be (0))

      await(5.seconds) { (1 to requests).map(send).toList.sequence }.sum should be (0)
      eventually(client.openedConnections should be (0))

      await(5.seconds) { (1 to requests).map(send).toList.sequence }.sum should be (0)
      eventually(client.openedConnections should be (0))
    }
    finally {
      client.stopSync()
    }

  }
}
