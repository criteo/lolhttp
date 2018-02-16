package lol.json

import lol.http._

import ServerSentEvents._

import cats.implicits._
import cats.effect.{ IO }
import fs2.{ Stream }

import io.circe._
import io.circe.syntax._
import io.circe.parser._
import io.circe.generic.auto._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.Codec

class JsonTests extends Tests {

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
    jsonContent.headers.get(Headers.ContentLength) should be (Some(HttpString(someJson.noSpaces.size)))

    val caseClassContent = Content.of(Blah("bar", 123, Seq(4, 5, 6)).asJson)
    caseClassContent.headers.get(Headers.ContentType) should be (Some(h"application/json; charset=UTF-8"))
    caseClassContent.headers.get(Headers.ContentLength) should be (Some(HttpString(someJson.noSpaces.size)))
  }

  test("JSON decoding") {
    val jsonContent = Content.of(someJson)
    jsonContent.as[Json].unsafeRunSync() should be (someJson)

    val invalidJsonContent = Content.of("""{lol}""")
    an [ParsingFailure] should be thrownBy invalidJsonContent.as[Json].unsafeRunSync()

    val blah = Blah("bar", 123, Seq(4, 5, 6))
    val caseClassContent = Content.of(blah.asJson)
    caseClassContent.as(json[Blah]).unsafeRunSync() should be (blah)
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
          req.readAs(json[Stuff]).
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
            r <- client.run(Get("/results"))(_.readAs(json[Payload]))
            _ = r.size should be (3)
            _ = r.data.find(_.id == 2).map(_.label) should be (Some("Lol"))

            r <- client.run(Post("/add", Stuff(8, "Bam").asJson))(res => IO.pure(res.status))
            _ = r should be (201)

            r <- client.run(Get("/results"))(_.readAs(json[Payload]))
            _ = r.size should be (4)
            _ = r.data.find(_.id == 8).map(_.label) should be (Some("Bam"))

            r <- client.run(Post("/add", "xxx"))(_.readAs[String])
            _ = r should startWith ("expected json value got x")

            r <- client.run(Post("/add", "{}"))(_.readAs[String])
            _ = r should startWith ("Attempt to decode value on failed cursor")
          } yield ()
        }
      }
    }
  }

  test("JSON over Server Sent Events") {
    withServer(Server.listen() {
      case url"/stream" =>
        Ok(Stream.covaryPure[IO, Event[Json], Event[Json]](Stream(Event(Json.obj("hello" -> "world".asJson)), Event(Json.Null), Event(12.asJson))))
    }) { server =>
      await() {
        Client("localhost", server.port).runAndStop { client =>
          client.run(Get("/stream")) { response =>
            response.readAs[Stream[IO,Event[Json]]].flatMap { eventStream =>
              eventStream.compile.toVector.map(_.toList)
            }
          }
        }
      } should be (List(Event(Json.obj("hello" -> "world".asJson)), Event(Json.Null), Event(12.asJson)))
    }
  }

  test("Max size is passed down to text encoder") {
    // with a decoder of 32 bytes
    val jsonDecoder = JsonContent.decoder(32, Codec.UTF8)

    // parsing 32 bytes should not throw
    jsonDecoder(validJsonContentOfSize(32).get).unsafeRunSync

    // but parsing 33 should
    a[ParsingFailure] should be thrownBy {
      jsonDecoder(validJsonContentOfSize(33).get).unsafeRunSync
    }
  }

  // generates valid JSON Content of size wantedSizeBytes
  private def validJsonContentOfSize(wantedSizeBytes: Int, encoding: String = "UTF-8"): Option[Content] = {
    val jsonLeft = """{"c":""""
    val jsonRight = """"}"""
    val templateSizeBytes = List(jsonLeft, jsonRight).map(_.getBytes(encoding).length).sum

    if (wantedSizeBytes <= templateSizeBytes)
      None
    else {
      val c: Stream[IO, Byte] = Stream.emit('a'.toByte)
        .repeat
        .take(wantedSizeBytes - templateSizeBytes)

      val contentStream: Stream[IO, Byte] = Stream.emits(jsonLeft.getBytes(encoding )) ++
        c ++
        Stream.emits(jsonRight.getBytes(encoding))

      Some(Content(contentStream))
    }
  }

}
