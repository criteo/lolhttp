// Example: Serving files from classpath
//
// This example show how to server static content files from
// the classpath. This is a common pattern to embed your web
// applications resources (html, js, css, etc.) into your application
// jar file, and to serve them on a __/public__ endpoint.
import lol.http._
import lol.html._

import scala.concurrent._
import ExecutionContext.Implicits.global

object ServingFiles {
  def main(args: Array[String]): Unit = {

    Server.listen(8888) {
      // Here we specifically serve our favicon image from the __/favicon__
      // endpoint. We do not need to check if the `ClasspathResource` actually
      // exists because we know statically that it exists.
      case GET at url"/favicon" =>
        Ok(ClasspathResource("/public/images/icon.gif"))

      // Here we dynamically extract the resource name to serve from the request URL.
      // We use __fold__ to handle the case where the resource does not exist. We have
      // to do that because the client could ask for a non existing resource and we want
      // to send a __404__ response in this case.
      //
      // Using `ClasspathResource` here is safe because it is not possible to navigate outside
      // of the __/public__ directory.
      //
      // Also, note how it is possible to use a `ClasspathResource` as content for a response.
      case GET at url"/assets/$file..." =>
        ClasspathResource(s"/public/$file").fold(NotFound)(r => Ok(r))

      case GET at url"/" =>
        Ok(html"""
          <head>
            <link rel="icon" href="/favicon" />
          </head>
          <body>
            <h1>The following image comes from the classpath:</h1>
            <img src="/assets/images/lol.gif">

            <h2>This one is missing:</h2>
            <img src="/assets/images/boo.gif">

            <h2>You can open this file:</h2>
            <a href="/assets/lol.txt">/assets/lol.txt</a>

            <h2>But this one is protected:</h2>
            <a href="/assets/../server-pkcs8.key">/assets/../server-pkcs8.key</a>
          </body>
        """)

      case _ =>
        NotFound
    }

    println("Listening on http://localhost:8888...")
  }
}
