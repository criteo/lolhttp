package lol.http

import scala.concurrent.{ ExecutionContext }
import ExecutionContext.Implicits.global

class SSLTests extends Tests {

  val App: Service = { request => Ok("Well done") }

  ignore("SSL over self signed certificate") {
    implicit val selfSignedSSL = SSL.selfSigned()

    withServer(Server.listen(ssl = Some(selfSignedSSL))(App)) { server =>
      contentString(Get(s"https://localhost:${server.port}/")) should be ("Well done")
    }
  }

  ignore("allow insecure connection") {
    implicit val trustAll = SSL.trustAll

    withServer(Server.listen(ssl = Some(SSL.selfSigned()))(App)) { server =>
      contentString(Get(s"https://localhost:${server.port}/")) should be ("Well done")
    }
  }

  ignore("insecure connection rejected") {
    withServer(Server.listen(ssl = Some(SSL.selfSigned()))(App)) { server =>
      an [Exception] should be thrownBy contentString(Get(s"https://localhost:${server.port}/"))
    }
  }

}
