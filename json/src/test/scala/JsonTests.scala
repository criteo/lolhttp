package lol.json

import lol.http._

import io.circe._
import io.circe.syntax._
import io.circe.parser._
import io.circe.generic.auto._

import scala.concurrent.{ Future, ExecutionContext }
import ExecutionContext.Implicits.global

class JsonTests extends   Tests {

  val someJson: Json = parse(
    """
      {
        "foo": "bar",
        "baz": 123,
        "lol": [ 4, 5, 6 ]
      }
    """
  ).right.getOrElse(Panic.!!!())

  case class Blah(foo: String, baz: Int, lol: Seq[Int])

  test("JSON encoding") {
    val jsonContent = Content.of(someJson)
    jsonContent.headers.get(Headers.ContentType) should be (Some(h"application/json; charset=UTF-8"))
    jsonContent.headers.get(Headers.ContentLength) should be (Some(HttpString(someJson.toString.size)))

    val caseClassContent = Content.of(Blah("bar", 123, Seq(4, 5, 6)).asJson)
    caseClassContent.headers.get(Headers.ContentType) should be (Some(h"application/json; charset=UTF-8"))
    caseClassContent.headers.get(Headers.ContentLength) should be (Some(HttpString(someJson.toString.size)))
  }

  test("JSON decoding") {
    val jsonContent = Content.of(someJson)
    jsonContent.as[Json].unsafeRun() should be (someJson)

    val invalidJsonContent = Content.of("""{lol}""")
    an [ParsingFailure] should be thrownBy invalidJsonContent.as[Json].unsafeRun()

    val blah = Blah("bar", 123, Seq(4, 5, 6))
    val caseClassContent = Content.of(blah.asJson)
    caseClassContent.as(json[Blah]).unsafeRun() should be (blah)
  }

  test("JSON over HTTP") {
    case class Payload(size: Int, data: Seq[Stuff])
    case class Stuff(id: Int, label: String)

    withServer(Server.listen() {
      val data = collection.mutable.ListBuffer(Stuff(1, "Ho"), Stuff(2, "Lol"), Stuff(3, "Youhou"));
      {
        case GET at "/results" =>
          Ok(Payload(data.size, data).asJson)
        case req @ POST at "/add" =>
          req.read(json[Stuff]).
            map { newStuff =>
              data += newStuff
              Created
            }.
            recover { case e =>
              InternalServerError(e.getMessage)
            }
      }
    }) { server =>
      await() {
        Client("localhost", server.port, maxConnections = 1).runAndStop { client =>
          for {
            r <- client.run(Get("/results"))(_.read(json[Payload]))
            _ = r.size should be (3)
            _ = r.data.find(_.id == 2).map(_.label) should be (Some("Lol"))

            r <- client.run(Post("/add", Stuff(8, "Bam").asJson))(res => Future.successful(res.status))
            _ = r should be (201)

            r <- client.run(Get("/results"))(_.read(json[Payload]))
            _ = r.size should be (4)
            _ = r.data.find(_.id == 8).map(_.label) should be (Some("Bam"))

            r <- client.run(Post("/add", "xxx"))(_.read[String])
            _ = r should startWith ("expected json value got x")

            r <- client.run(Post("/add", "{}"))(_.read[String])
            _ = r should startWith ("Attempt to decode value on failed cursor")
          } yield ()
        }
      }
    }
  }

}
