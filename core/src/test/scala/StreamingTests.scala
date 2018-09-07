package lol.http.examples

import lol.http._

import cats.effect.{ IO }
import fs2.{ Chunk, Pipe, Pull, Stream }

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class StreamingTests extends Tests {

  def now = System.currentTimeMillis
  val `10Meg` = 10 * 1024 * 1024

  // Transform the stream into packets of 10Meg
  def rechunk: Pipe[IO,Byte,Chunk[Byte]] =
    _.repeatPull(_.unconsN(`10Meg`, true).flatMap {
      case Some((chunks, h)) =>
        Pull.output1(chunks) as Some(h)
      case None =>
        Pull.pure(None)
    })

  test("Slow server read", Slow) {
    withServer(Server.listen() { req =>
      val start = now
      // Read at most 3Meg per second
      req.read(
        _.through(rechunk).
          evalMap(c => IO(println(s"${c.size} bytes received"))).
          flatMap(_ => Stream.sleep[IO](3.seconds))
          .compile.drain
      ).map { _ =>
        Ok(s"${now - start}")
      }
    }) { server =>
      val start = now
      var end = 0:Long

      // Send 100M as fast as possible
      val timeToReceive = contentString(
        Post(
          s"http://localhost:${server.port}/",
          content = Content(
            stream = Stream.eval(IO {
              println(s"${`10Meg`} bytes sent")
              end = now
              Chunk.bytes(("." * `10Meg`).getBytes)
            }).repeat.take(10).flatMap(c => Stream.chunk(c))
          )
        ).addHeaders(h"Content-Length" -> h"${10 * `10Meg`}"),
        atMost = 2.minutes
      ).toInt
      val timeToSend = (end - start).toInt

      println(s"Received in ${timeToReceive/1000}s")
      println(s"Sent in ${timeToSend/1000}s")

      timeToReceive should be > 25000
      timeToSend should be > 15000
    }
  }
  test("Client read compressed", Slow) {
    withServer(Server.listen() { req =>
      Ok(Content(Stream.eval(IO {
        println(s"sent ${`10Meg`} bytes")
        Chunk.bytes(("." * `10Meg`).getBytes)
      }).repeat.take(10).flatMap(c => Stream.chunk(c))))
        .addHeaders(h"Content-Length" -> h"${10 * `10Meg`}")
    }) { server =>
      await(atMost = 2.minutes) {
        Client("localhost", server.port).runAndStop { client =>
          for {
            length1 <- client.run(Get("/a"))(_.readSuccess { stream =>
              stream.chunks.map(_.size).compile.fold(0)(_ + _)
            })
            length2 <- client.run(Get("/b").addHeaders(h"Accept-Encoding" -> h"gzip"))(_.readSuccess { stream =>
              stream.chunks.map(_.size).compile.fold(0)(_ + _)
            })
            length3 <- client.run(Get("/c").addHeaders(h"Accept-Encoding" -> h"deflate"))(_.readSuccess { stream =>
              stream.chunks.map(_.size).compile.fold(0)(_ + _)
            })
          } yield {
            length1 shouldEqual 10 * `10Meg`
            length2 shouldEqual length1
            length3 shouldEqual length1
          }
        }
      }
    }
  }

}
