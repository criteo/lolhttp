package lol.http

import ServerSentEvents._

import cats.effect.IO
import fs2.{ Stream }

import scala.concurrent.{ ExecutionContext }
import ExecutionContext.Implicits.global

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
                eventStream.runLog.map(_.toList).unsafeToFuture
              }
            }
          }
        } should be (List(Event("Hello"), Event("World")))
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
                eventStream.runLog.map(_.toList).unsafeToFuture
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
                eventStream.runLog.map(_.toList).unsafeToFuture
              }
            }
          }
        } should be (Nil)
      }
    }
  }

}
