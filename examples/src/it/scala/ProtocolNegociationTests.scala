package lol.http.examples

import lol.http._

import scala.concurrent.{ ExecutionContext }
import ExecutionContext.Implicits.global

import scala.sys.process._

class ProtocolNegociationTests extends Tests {

  val app: Service = req => Ok(s"Hello ${req.protocol}")

  test("HTTP/1.1 only") {
    withServer(Server.listen(options = ServerOptions(protocols = Set(HTTP)))(app)) { server =>
      s"curl http://localhost:${server.port}/".!!.trim should be ("Hello HTTP/1.1")
      s"curl --http2-prior-knowledge http://localhost:${server.port}/".! should not be (0)
    }
  }

  test("HTTP/1.1 & HTTP/2") {
    withServer(Server.listen(options = ServerOptions(protocols = Set(HTTP, HTTP2)))(app)) { server =>
      s"curl http://localhost:${server.port}/".! should be (0)
      s"curl http://localhost:${server.port}/".!!.trim should be ("Hello HTTP/1.1")
      s"curl --http2-prior-knowledge http://localhost:${server.port}/".!!.trim should be ("Hello HTTP/2")
    }
  }

  test("HTTP/2 only") {
    withServer(Server.listen(options = ServerOptions(protocols = Set(HTTP2)))(app)) { server =>
      s"curl http://localhost:${server.port}/".! should not be (0)
      s"curl --http2-prior-knowledge http://localhost:${server.port}/".!!.trim should be ("Hello HTTP/2")
    }
  }

  test("SSL + HTTP/1.1 only") {
    withServer(Server.listen(ssl = Some(SSL.selfSigned()), options = ServerOptions(protocols = Set(HTTP)))(app)) { server =>
      s"curl --insecure https://localhost:${server.port}/".!!.trim should be ("Hello HTTP/1.1")
      s"curl --http2-prior-knowledge --insecure https://localhost:${server.port}/".! should not be (0)
    }
  }

  test("SSL + HTTP/1.1 & HTTP/2") {
    withServer(Server.listen(ssl = Some(SSL.selfSigned()), options = ServerOptions(protocols = Set(HTTP, HTTP2)))(app)) { server =>
      s"curl --insecure https://localhost:${server.port}/".!!.trim should be ("Hello HTTP/2")
      s"curl --http2-prior-knowledge --insecure https://localhost:${server.port}/".!!.trim should be ("Hello HTTP/2")
    }
  }

  test("SSL + HTTP/2 only") {
    withServer(Server.listen(ssl = Some(SSL.selfSigned()), options = ServerOptions(protocols = Set(HTTP2)))(app)) { server =>
      s"curl --insecure https://localhost:${server.port}/".!!.trim should be ("Hello HTTP/2")
      s"curl --http2-prior-knowledge --insecure https://localhost:${server.port}/".!!.trim should be ("Hello HTTP/2")
    }
  }

}