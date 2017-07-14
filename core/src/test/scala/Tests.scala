package lol.http

import org.scalatest._

import java.util.{ Timer, TimerTask }

import scala.util.{ Try, Success, Failure }

import scala.concurrent.{ Await, Promise, Future, ExecutionContext }
import scala.concurrent.duration._

abstract class Tests extends FunSuite with Matchers with OptionValues with Inside with Inspectors {
  val Pure = Tag("Pure")
  val Slow = Tag("Slow")
  val Unsafe = Tag("Unsafe")
  def await[A](atMost: Duration = 30 seconds)(a: Future[A]): A = Await.result(a, atMost)
  def withServer(server: Server)(test: Server => Unit) = try { test(server) } finally { server.stop() }
  def success[A](a: A) = Future.successful(a)
  def status(req: Request, atMost: Duration = 30 seconds, followRedirects: Boolean = true)(implicit e: ExecutionContext, ssl: SSL.ClientConfiguration): Int = {
    await(atMost) { Client.run(req, followRedirects = followRedirects)(res => success(res.status)) }
  }
  def contentString(req: Request, atMost: Duration = 30 seconds, followRedirects: Boolean = true)(implicit e: ExecutionContext, ssl: SSL.ClientConfiguration): String = {
    await(atMost) { Client.run(req, followRedirects = followRedirects)(_.readAs[String]) }
  }
  def headers(req: Request, atMost: Duration = 30 seconds)(implicit e: ExecutionContext, ssl: SSL.ClientConfiguration): Map[HttpString,HttpString] = {
    await(atMost) { Client.run(req)(res => Future.successful(res.headers)) }
  }
  def getString(content: Content, codec: String = "utf-8") = new String(getBytes(content).toArray, codec)
  def getBytes(content: Content): Vector[Byte] = content.stream.runLog.unsafeRunSync()
  def bytes(data: Int*): Seq[Byte] = data.map(_.toByte)
  val timer = new Timer(true)
  def timeout[A](d: Duration, a: A): Future[A] = {
    val p = Promise[A]
    timer.schedule(new TimerTask { def run() = p.success(a) }, d.toMillis)
    p.future
  }
  def eventually[A](assertion: => A, timeout: Duration = 5 seconds): A = {
    val start = System.currentTimeMillis
    def go(): A = Try(assertion) match {
      case Success(a) => a
      case Failure(e) =>
        if(System.currentTimeMillis - start < timeout.toMillis) go() else throw e
    }
    go()
  }
}
