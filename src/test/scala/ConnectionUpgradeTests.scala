package lol.http

import fs2.{ Stream }
import fs2.text.{ lines, utf8Decode, utf8Encode }

import scala.concurrent.{ ExecutionContext }
import ExecutionContext.Implicits.global

class ConnectionUpgradeTests extends Tests {

  val App: Service = {

    case GET at "/" =>
      Ok("Home")

    case request @ GET at "/echo" =>
      request.headers.get(Headers.Upgrade) match {
        case Some("ReverseEcho") =>
          SwitchingProtocol("ReverseEcho", {
            _ through utf8Decode through lines map (msg => s"${msg.reverse}\n") through utf8Encode
          })
        case _ =>
          UpgradeRequired("ReverseEcho")
      }

    case _ =>
      NotFound
  }

  test("Upgrade connection") {
    withServer(Server.listen()(App)) { server =>
      val url = s"http://localhost:${server.port}/echo"

      await {
        Client.run(Get(url).addHeaders(Headers.Upgrade -> "ReverseEcho")) { response =>
          response.status should be (101)
          response.headers.get(Headers.Upgrade) should be (Some("ReverseEcho"))

          val upstream = Stream("Hello", " world\nlol", "\n", "wat??", "\n", "DONE").pure through utf8Encode
          val downstream = response.upgradeConnection(upstream) through utf8Decode through lines

          downstream.runLog.unsafeRunAsyncFuture()
        }
      } should contain inOrderOnly (
        "dlrow olleH",
        "lol",
        "??taw",
        "ENOD",
        "" // because of the way `text.lines` works we get this final chunk
      )
    }
  }

  ignore("Server refuse to upgrade") {
    withServer(Server.listen()(App)) { server =>
      val url = s"http://localhost:${server.port}/"

      await {
        Client.run(Get(url).addHeaders(Headers.Upgrade -> "ReverseEcho")) { response =>
          response.status should not be (101)
          response.headers.get(Headers.Upgrade) should be (None)

          // Upgrade anyway :)
          val upstream = Stream.pure("watever\n", "oops\n", "bad protocol\n") through utf8Encode
          val downstream = response.upgradeConnection(upstream) through utf8Decode through lines

          downstream.runLog.unsafeRunAsyncFuture()
        }
      } should contain inOrder (
        "HomeHTTP/1.1 400 Bad Request", // the original response content, directly followed by a 400 response
        "Connection: close" // the server ask to close the connection
      )
    }
  }

}
