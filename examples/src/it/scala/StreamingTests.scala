package lol.http.examples

import lol.http._

import cats.effect.{ IO }
import fs2.{ Chunk, Pipe, Pull, Stream, Scheduler }

import scala.concurrent.{ ExecutionContext }
import scala.concurrent.duration._

import ExecutionContext.Implicits.global

class StreamingTests extends Tests {

  def now = System.currentTimeMillis
  val `10Meg` = 10 * 1024 * 1024

  // Transform the stream into packets of 10Meg
  def rechunk: Pipe[IO,Byte,Chunk[Byte]] =
    _.repeatPull(_.unconsN(`10Meg`, true).flatMap {
      case Some((chunks, h)) =>
        Pull.output1(chunks.toChunk) as Some(h)
      case None =>
        Pull.pure(None)
    })

  test("Slow server read", Slow) {
    foreachProtocol(HTTP, HTTP2) { protocol =>
      println(s"========= $protocol")
      withServer(Server.listen(options = ServerOptions(protocols = Set(protocol))) { req =>
        val start = now
        // Read at most 3Meg per second
        req.read(stream => Scheduler[IO](corePoolSize = 1).flatMap { scheduler =>
          stream.
            through(rechunk).
            evalMap(c => IO(println(s"${c.size} bytes received"))).
            flatMap(_ => scheduler.sleep[IO](3.seconds))
        }.run).map { _ =>
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
          atMost = 2.minutes,
          protocol = protocol
        ).toInt
        val timeToSend = (end - start).toInt

        println(s"Received in ${timeToReceive/1000}s")
        println(s"Sent in ${timeToSend/1000}s")

        timeToReceive should be > 30000
        timeToSend should be > 20000
      }
    }
  }

}