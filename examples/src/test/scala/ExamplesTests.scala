package lol.http.examples

object ExamplesTests {

  def main(args: Array[String]): Unit = {
    val example = args.headOption.getOrElse(sys.error("Please specify the example to run as argument"))
    val forked = runExample(example)

    new Thread() {
      override def run: Unit = {
        println(s"-- example `$example` started, press [Ctrl+D] to quit")
        while (System.in.read != -1) ()
        forked.destroy()
      }
    }.start()

    forked.waitFor
    System.exit(0)
  }

  def runExample(className: String): Process =
    new ProcessBuilder("java", "-cp", System.getProperty("java.class.path"), className).inheritIO.start()

}

import lol.http._
import lol.json._

import fs2._
import io.circe._

import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global

class ExamplesTests extends Tests  {
  import ExamplesTests.runExample

  test("HelloWorld", Unsafe) {
    val forked = runExample("HelloWorld")
    val requests = 100

    try {
      val responses = eventually(await() {
        Client("localhost", 8888).runAndStop { client =>
          Future.sequence {
            (1 to requests).map(_ => client.run(Get("/"))(_.readAs[String]))
          }
        }
      }, timeout = 30 seconds)

      responses should be ((1 to requests).map(_ => "Hello world!"))
    }
    finally {
      forked.destroy
    }
  }

  test("LargeFileUpload", Unsafe) {
    val forked = runExample("LargeFileUpload")
    val message = Chunk.bytes("HELLO".getBytes)
    val chunk = Chunk.bytes(("A" * 1024).getBytes)
    val sizes = List(0, 1, 100, 1024, 10240)

    try {
      val responses = eventually(await() {
        Client.run(Get("http://localhost:8888/"))(res => Future.successful(res.status).filter(_ == 200)).flatMap { _ =>
          Client("localhost", 8888, maxConnections = 1).runAndStop { client =>
            Future.sequence {
              sizes.map { i =>
                val fakeContent = Content(
                  Stream.chunk(message) ++ (Stream.chunk(chunk).repeat.take(i * chunk.size))
                ).addHeaders(h"Content-Length" -> h"${message.size + (i * chunk.size)}")
                client.run(Post("/upload", fakeContent))(_.readAs[String])
              }
            }
          }
        }
      }, timeout = 30 seconds)

      responses should be (sizes.map { i =>
        s"<h1>Done! body size was ${message.size + (i * chunk.size)}</h1>"
      })
    }
    finally {
      forked.destroy
    }
  }

  test("ServingFiles", Unsafe) {
    val forked = runExample("ServingFiles")

    try {
      val (index, favicon, booStatus, lol, brokenStatus) = eventually(await() {
        for {
          index <-
            Client.run(Get("http://localhost:8888/")) { res =>
              res.readAs[String]
            }
          favicon <-
            Client.run(Get("http://localhost:8888/favicon")) { res =>
              res.readAs[Array[Byte]]
            }
          booStatus <-
            Client.run(Get("http://localhost:8888/assets/images/boo.gif")) { res =>
              Future.successful(res.status)
            }
          lol <-
            Client.run(Get("http://localhost:8888/assets/lol.txt")) { res =>
              res.readAs[Array[Byte]]
            }
          brokenStatus <-
            Client.run(Get("http://localhost:8888/assets/../secure.txt")) { res =>
              Future.successful(res.status)
            }
        } yield (index, favicon, booStatus, lol, brokenStatus)
      }, timeout = 30 seconds)

      favicon.size should be (336)
      index should include ("But this one is protected")
      booStatus should be (404)
      lol should be ("LOL".getBytes)
      brokenStatus should be (404)
    }
    finally {
      forked.destroy
    }
  }

  test("ReverseProxy", Unsafe) {
    val forked = runExample("ReverseProxy")
    try {
      val responses = eventually(await() {
        Future.sequence {
          (1 to 15).map { _ =>
            Client.run(Get("http://localhost:8888/"), followRedirects = true)(_.readAs[String])
          }
        }
      }, timeout = 30 seconds)

      responses.foreach(_ should include ("<title>Criteo - Wikipedia</title>"))
    }
    finally {
      forked.destroy
    }
  }

  test("JsonWebService", Unsafe) {
    val forked = runExample("JsonWebService")
    val client = Client("localhost", 8888)
    try {
      eventually(await() { client.run(Get("/"))(_.readSuccessAs[String]) }, timeout = 30 seconds) should include ("Nothing to do")
      await() { client.run(Get("/api/todos"))(_.readSuccessAs(json[Seq[Json]])) } should be (empty)
      await() { client.run(Post("/api/todos", Json.obj("text" -> Json.fromString("Yo"))))(_.assertSuccess) } should be (())
      await() { client.run(Get("/"))(_.readSuccessAs[String]) } should include ("A ton of things to do")
      await() { client.run(Get("/"))(_.readSuccessAs[String]) } should include ("Yo")
      await() { client.run(Get("/api/todos"))(_.readSuccessAs(json[Seq[Json]])) }.size should be (1)
      await() { client.run(Get("/api/todos?done=false"))(_.readSuccessAs(json[Seq[Json]])) }.size should be (1)
      await() { client.run(Get("/api/todos?done=true"))(_.readSuccessAs(json[Seq[Json]])) }.size should be (0)
      await() { client.run(Post("/api/todos/1", Json.obj("done" -> Json.fromBoolean(true))))(_.assertSuccess) } should be (())
      await() { client.run(Get("/api/todos?done=false"))(_.readSuccessAs(json[Seq[Json]])) }.size should be (0)
      await() { client.run(Get("/api/todos?done=true"))(_.readSuccessAs(json[Seq[Json]])) }.size should be (1)
      await() { client.run(Delete("/api/todos/1"))(_.assertSuccess) } should be (())
      await() { client.run(Get("/api/todos"))(_.readSuccessAs(json[Seq[Json]])) } should be (empty)
      await() { client.run(Get("/"))(_.readSuccessAs[String]) } should include ("Nothing to do")
    }
    finally {
      client.stop()
      forked.destroy
    }
  }

  test("GithubClient", Unsafe) {
    val forked = runExample("GithubClient")
    forked.waitFor should be (0)
  }

}
