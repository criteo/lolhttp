package lol.http


import cats.effect.IO
import fs2.{ Scheduler, Stream }
import lol.http.ServerSentEvents._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class ServerSentEventsTests extends Tests {

  val App: Service = {
    case url"/" =>
      Ok("Hello")
    case url"/stream" =>
      Ok(Stream.covaryPure[IO, Event[String], Event[String]](Stream(Event("Hello"), Event("World"))))
    case url"/fakeStream" =>
      Ok("Hello").addHeaders(h"Content-Type" -> h"text/event-stream")
  }

  test("Valid string events stream") {
    foreachProtocol(HTTP, HTTP2) { protocol =>
      withServer(Server.listen(options = ServerOptions(protocols = Set(protocol)))(App)) { server =>
        await() {
          Client("localhost", server.port, options = ClientOptions(protocols = Set(protocol))).runAndStop { client =>
            client.run(Get("/stream")) { response =>
              response.readAs[Stream[IO,Event[String]]].flatMap { eventStream =>
                eventStream.compile.toVector.map(_.toList)
              }
            }
          }
        } should be (List(Event("Hello"), Event("World")))
      }
    }
  }

  test("Events stream that sends nothing should be stopped by server when client closes the connection") {
    foreachProtocol(HTTP, HTTP2) { protocol =>
      val isRunning = fs2.async.signalOf[IO, Boolean](true).unsafeRunSync()

      val App: Service = {
        case url"/streamThatSendsNothing" =>
          val emptyInfiniteStream: Stream[IO,Nothing] =
            Scheduler[IO](corePoolSize = 1).flatMap { scheduler =>
              scheduler.sleep[IO](100.milliseconds).flatMap(_ => Stream.empty).repeat
            }
          Ok(emptyInfiniteStream.onFinalize(isRunning.set(false)))
      }

      withServer(Server.listen(options = ServerOptions(protocols = Set(protocol)))(App)) { server =>
        await() {
          val client = Client("localhost", server.port, options = ClientOptions(protocols = Set(protocol)))
          timeout(client.stopSync(), 1.second).unsafeRunAsync(_ => ())
          client.run(Get("/streamThatSendsNothing")) { response =>
            response.readAs[Stream[IO,Event[String]]].flatMap { eventStream =>
              eventStream.compile.toVector.map { e =>
                e.toList
              }
            }
          }
        }

        eventually({
          val t = isRunning.get.unsafeRunSync()
          t shouldBe false
        })
      }
    }
  }

  test("Not an events stream") {
    foreachProtocol(HTTP, HTTP2) { protocol =>
      withServer(Server.listen(options = ServerOptions(protocols = Set(protocol)))(App)) { server =>
        the [Error] thrownBy await() {
          Client("localhost", server.port, options = ClientOptions(protocols = Set(protocol))).runAndStop { client =>
            client.run(Get("/")) { response =>
              response.readAs[Stream[IO,Event[String]]].flatMap { eventStream =>
                eventStream.compile.toVector.map(_.toList)
              }
            }
          }
        } should be (Error.UnexpectedContentType())
      }
    }
  }

  test("Invalid events stream ") {
    foreachProtocol(HTTP, HTTP2) { protocol =>
      withServer(Server.listen(options = ServerOptions(protocols = Set(protocol)))(App)) { server =>
        await() {
          Client("localhost", server.port, options = ClientOptions(protocols = Set(protocol))).runAndStop { client =>
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

}
