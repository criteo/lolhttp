// Example: Hello world!

// Let's write a simple HTTP service that return the __Hello world!__
// text for any request.
import lol.http._

// We need to have an `ExecutionContext` context in the scope.
// It will be used to execute the user code. We can use
// the default Scala global one.
import scala.concurrent._
import ExecutionContext.Implicits.global

object HelloWorld {
  def main(args: Array[String]): Unit = {

    // Here we start an HTTP server on the port _8888_.
    // We pass a [`(Request) => Future[Response]`]() function to
    // handle the requests.
    Server.listen(8888) { req => 

      // For each request, we just return a synchronous
      // 200 OK response, with a _text/plain_ content.
      Ok("Hello world!") 
    }

  }
}
