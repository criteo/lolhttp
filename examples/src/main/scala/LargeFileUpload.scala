// Example: Large file upload

// In this example we accept a very large file upload and
// we compute the total request body size without having to
// put everything in memory.
//
// This is the main advantage of the lolhttp API. All the request
// body processing is done in user land, and you can use fs2 to
// manipulate the incoming stream.
import lol.http._
import lol.html._

import scala.concurrent.ExecutionContext.Implicits.global

// - - -
object LargeFileUpload {
  def main(args: Array[String]): Unit = {

    // First, we start an HTTP server. We use a partial function
    // for our service implementation to allow us to pattern match
    // the incoming request.
    Server.listen(8888) {

      // In case of a __GET /__ request, we return a simple HTML form allowing
      // to upload a file.
      case GET at url"/" => {
        Ok(
          tmpl"""
            <h1>Try to upload a very large file</h1>
            <p>And use your browser network conditions to simulate a slow client</p>
            <form action="/upload" enctype="multipart/form-data" method="POST">
              <input type="file" name="data">
              <input type="submit">
            </form>
          """
        )
      }

      // In case of a __POST /upload__ request, we consume the request body with a special
      // reader that accumulates the total body size.
      case request @ POST at url"/upload" => {
        request.read(_.chunks.compile.fold(0: Long)(_ + _.size)).map { size =>
          // Once the whole body has been read, the total size has been computed,
          // and we display it in an HTML response.
          Ok(tmpl"<h1>Done! body size was @size</h1>")
        }
      }
    }

    println("Listening on http://localhost:8888...")
  }
}
