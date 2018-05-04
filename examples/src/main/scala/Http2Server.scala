// Example: HTTP/2 server

// Let's write a simple HTTP/2 server that allow to upload
// small files in the server memory.
//
// Most (all?) browsers will upgrade to HTTP/2 over TLS only,
// using the Application-Layer Protocol Negotiation. So we need
// to setup HTTPS.
import lol.http._
import lol.html._

import scala.concurrent.ExecutionContext.Implicits.global

object Http2Server {
  def main(args: Array[String]): Unit = {

    // First we create a server SSL configuration from a dummy
    // certificate stored on disk.
    val ssl = SSL.serverCertificate(
      certificatePath = "src/main/resources/server.crt",
      // Here is the key private key,
      privateKeyPath = "src/main/resources/server-pkcs8.key",
      // and he password to access the key.
      privateKeyPassword = "lol"
    )

    // By specifying the `protocols` options we allow this server to handle both HTTP/1.1 & HTTP/2.
    Server.listen(8443, ssl = Some(ssl), options = ServerOptions(protocols = Set(HTTP, HTTP2))) {

      // The home page display the current protocol using `req.protocol`.
      case req @ url"/" =>
        Ok(
          tmpl"""
            <h1>Hello @req.protocol</h1>
            <form action="/upload" method="POST" enctype="multipart/form-data">
              <input type="file" name="lol" />
              <input type="submit" />
            </form>
          """
        )

      // Now we read the request content into memory. The default size limitation
      // apply here (usually up to 1MB).
      case req @ url"/upload" =>
        req.readAs[Array[Byte]].map { bytes =>
          Ok(tmpl"Received @bytes.size bytes")
        }

      case _ =>
        NotFound
    }

    println("Listening on https://localhost:8443...")
  }
}
