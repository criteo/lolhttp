package lol.http

import org.scalatest._

import java.util.{ Timer, TimerTask }

import scala.util.{ Try, Success, Failure }

import scala.concurrent.{ Await, Promise, Future, ExecutionContext }
import scala.concurrent.duration._

abstract class Tests extends FunSuite with Matchers with OptionValues with Inside with Inspectors {
  val Pure = Tag("Pure")
  val Slow = Tag("Slow")
  def await[A](atMost: FiniteDuration = 30 seconds)(a: Future[A]): A = Await.result(a, atMost)
  def withServer(server: Server)(test: Server => Unit) = try { test(server) } finally { server.stop() }
  def success[A](a: A) = Future.successful(a)
  def status(req: Request, atMost: FiniteDuration = 30 seconds, followRedirects: Boolean = true)(implicit e: ExecutionContext, ssl: SSL.ClientConfiguration): Int = {
    await(atMost) { Client.run(req, followRedirects = followRedirects, timeout = atMost)(res => success(res.status)) }
  }
  def contentString(req: Request, atMost: FiniteDuration = 30 seconds, followRedirects: Boolean = true)(implicit e: ExecutionContext, ssl: SSL.ClientConfiguration): String = {
    await(atMost) { Client.run(req, followRedirects = followRedirects, timeout = atMost)(_.readAs[String]) }
  }
  def headers(req: Request, atMost: FiniteDuration = 30 seconds)(implicit e: ExecutionContext, ssl: SSL.ClientConfiguration): Map[HttpString,HttpString] = {
    await(atMost) { Client.run(req, timeout = atMost)(res => Future.successful(res.headers)) }
  }
  def getString(content: Content, codec: String = "utf-8") = new String(getBytes(content).toArray, codec)
  def getBytes(content: Content): Vector[Byte] = content.stream.runLog.unsafeRunSync()
  def bytes(data: Int*): Seq[Byte] = data.map(_.toByte)
  val timer = new Timer(true)
  def timeout[A](d: FiniteDuration, a: A): Future[A] = {
    val p = Promise[A]
    timer.schedule(new TimerTask { def run() = p.success(a) }, d.toMillis)
    p.future
  }
  def eventually[A](assertion: => A, timeout: FiniteDuration = 5 seconds): A = {
    val start = System.currentTimeMillis
    def go(): A = Try(assertion) match {
      case Success(a) => a
      case Failure(e) =>
        if(System.currentTimeMillis - start < timeout.toMillis) go() else throw e
    }
    go()
  }
}
