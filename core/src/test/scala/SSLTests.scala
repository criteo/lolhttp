package lol.http

import scala.concurrent.{ ExecutionContext }
import ExecutionContext.Implicits.global

class SSLTests extends Tests {

  val App: Service = { request => Ok("Well done") }

  test("SSL over self signed certificate") {
    implicit val trustAll = SSL.trustAll
    withServer(Server.listen(ssl = Some(SSL.selfSigned()))(App)) { server =>
      contentString(Get(s"https://localhost:${server.port}/")) should be ("Well done")
    }
  }

  test("insecure connection rejected") {
    withServer(Server.listen(ssl = Some(SSL.selfSigned()))(App)) { server =>
      an [Exception] should be thrownBy contentString(Get(s"https://localhost:${server.port}/"))
    }
  }

}
