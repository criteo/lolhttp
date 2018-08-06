package lol.http


import cats.implicits._
import cats.effect.IO
import fs2.{ Chunk, Stream }
import lol.http.ServerSentEvents._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class ServerSentEventsTests extends Tests {

  val App: Service = {
    case url"/" =>
      Ok("Hello")
    case url"/stream" =>
      Ok(Stream(Event("Hello"), Event("World")).covaryAll[IO, Event[String]])
    case url"/fakeStream" =>
      Ok("Hello").addHeaders(h"Content-Type" -> h"text/event-stream")
  }

  test("Valid string events stream") {
    withServer(Server.listen()(App)) { server =>
      await() {
        Client("localhost", server.port).runAndStop { client =>
          client.run(Get("/stream")) { response =>
            response.readAs[Stream[IO,Event[String]]].flatMap { eventStream =>
              eventStream.compile.toVector.map(_.toList)
            }
          }
        }
      } should be (List(Event("Hello"), Event("World")))
    }
  }

  test("Events stream should be stopped by server when client closes the connection") {
    val isRunning = fs2.async.signalOf[IO, Boolean](true).unsafeRunSync()

    val App: Service = {
      case url"/infiniteStream" =>
        val infiniteStream =
          Stream.sleep[IO](100.milliseconds).flatMap(_ => Stream.chunk(Chunk.bytes("LOL\n".getBytes("utf-8")))).repeat
        Ok(Content(infiniteStream.onFinalize(isRunning.set(false))))
    }

    withServer(Server.listen()(App)) { server =>
      await() {
        val client = Client("localhost", server.port)
        (IO.sleep(1.second) >> IO(client.stopSync())).unsafeRunAsync(_ => ())
        client.run(Get("/infiniteStream")) { response =>
          response.readAs[String]
        }
      }

      eventually({
        val t = isRunning.get.unsafeRunSync()
        t shouldBe false
      })
    }
  }

  test("Not an events stream") {
    withServer(Server.listen()(App)) { server =>
      the [Error] thrownBy await() {
        Client("localhost", server.port).runAndStop { client =>
          client.run(Get("/")) { response =>
            response.readAs[Stream[IO,Event[String]]].flatMap { eventStream =>
              eventStream.compile.toVector.map(_.toList)
            }
          }
        }
      } should be (Error.UnexpectedContentType())
    }
  }

  test("Invalid events stream ") {
    withServer(Server.listen()(App)) { server =>
      await() {
        Client("localhost", server.port).runAndStop { client =>
          client.run(Get("/fakeStream")) { response =>
            response.readAs[Stream[IO,Event[String]]].flatMap { eventStream =>
              eventStream.compile.toVector.map(_.toList)
            }
          }
        }
      } should be (Nil)
    }
  }

}
