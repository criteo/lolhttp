import lol.http._

import scala.concurrent._
import ExecutionContext.Implicits.global

object HelloWorld {
  def main(args: Array[String]): Unit = {

    Server.listen(8888) { req => Ok("Hello world!") }

  }
}
