import lol.http._

import concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ResponseTests extends Tests {
  test("Future.map on a Response") {
    def addH1(response: Response) = response.addHeaders(h"a" -> h"b")

    def addH2(response: Response) = response.addHeaders(h"1" -> h"2")

    val res = Response(418)
    await() {
      res map (addH2 _ compose addH1 _)
    } shouldEqual await() {
      res map addH2 map addH1
    }
  }

  test("Future.flatMap on a Response") {
    val res = Response(418)
    await() {
      res flatMap (Future(_))
    } shouldEqual res
  }
}
