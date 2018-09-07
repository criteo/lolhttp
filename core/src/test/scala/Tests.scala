package lol.http

import cats.effect.IO

import org.scalatest._

import scala.util.{Try, Success, Failure}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

abstract class Tests extends FunSuite with Matchers with OptionValues with Inside with Inspectors {
  val Pure = Tag("Pure")
  val Slow = Tag("Slow")
  def await[A](atMost: FiniteDuration = 30.seconds)(a: IO[A]): A = Await.result(a.unsafeToFuture, atMost)
  def withServer(server: Server)(test: Server => Unit) = try { test(server) } finally { server.stop() }
  def status(req: Request, atMost: FiniteDuration = 30.seconds, followRedirects: Boolean = true, protocol: String = HTTP)(implicit e: ExecutionContext, ssl: SSL.ClientConfiguration): Int = {
    await(atMost) { Client.run(req, followRedirects = followRedirects, timeout = atMost, protocol = protocol)(res => IO.pure(res.status)) }
  }
  def contentString(req: Request, atMost: FiniteDuration = 30.seconds, followRedirects: Boolean = true, protocol: String = HTTP)(implicit e: ExecutionContext, ssl: SSL.ClientConfiguration): String = {
    await(atMost) { Client.run(req, followRedirects = followRedirects, timeout = atMost, protocol = protocol)(_.readAs[String]) }
  }
  def headers(req: Request, atMost: FiniteDuration = 30.seconds, protocol: String = HTTP)(implicit e: ExecutionContext, ssl: SSL.ClientConfiguration): Map[HttpString,HttpString] = {
    await(atMost) { Client.run(req, timeout = atMost, protocol = protocol)(res => IO.pure(res.headers)) }
  }
  def header(req: Request, header: HttpString, atMost: FiniteDuration = 30.seconds, protocol: String = HTTP)(implicit e: ExecutionContext, ssl: SSL.ClientConfiguration): Option[HttpString] = {
    await(atMost) { Client.run(req, timeout = atMost, protocol = protocol)(res => IO.pure(res.headers.get(header))) }
  }
  def getString(content: Content, codec: String = "utf-8") = new String(getBytes(content).toArray, codec)
  def getBytes(content: Content): Vector[Byte] = content.stream.compile.toVector.unsafeRunSync()
  def bytes(data: Int*): Seq[Byte] = data.map(_.toByte)
  def eventually[A](assertion: => A, timeout: FiniteDuration = 5.seconds): A = {
    val start = System.currentTimeMillis
    def go(): A = Try(assertion) match {
      case Success(a) => a
      case Failure(e) =>
        if(System.currentTimeMillis - start < timeout.toMillis) go() else throw e
    }
    go()
  }
}
