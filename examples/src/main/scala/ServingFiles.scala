// Example: Serving files
import lol.http._
import lol.html._

import scala.concurrent._
import ExecutionContext.Implicits.global

object ServingFiles {
  def main(args: Array[String]): Unit = {

    Server.listen(8888) {
      case GET at url"/" =>
        Ok(html"""
          <head>
            <link rel="icon" href="/favicon" />
          </head>
          <body>
            <h1>The following image comes from the classpath:</h1>
            <img src="/assets/lol.gif">

            <h2>This one is missing:</h2>
            <img src="/assets/boo.gif">

            <h2>You can open this file:</h2>
            <a href="/assets/lol.txt">/assets/lol.txt</a>

            <h2>But this one is protected:</h2>
            <a href="/assets/../secure.txt">/assets/../secure.txt</a>
          </body>
        """)

      case GET at url"/favicon" =>
        Ok(ClasspathResource("/public/icon.gif"))

      case GET at url"/assets/$file" =>
        ClasspathResource(s"/public/$file").fold(NotFound)(r => Ok(r))

      case _ =>
        NotFound
    }

    println("Listening on http://localhost:8888...")
  }
}
