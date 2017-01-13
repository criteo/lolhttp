package lol.http

import org.scalatest._

import java.util.{ Timer, TimerTask }

import scala.concurrent.{ Await, Promise, Future, ExecutionContext }
import scala.concurrent.duration._

abstract class Tests extends FunSuite with Matchers with OptionValues with Inside with Inspectors {
  def await[A](a: Future[A], atMost: Duration = 5 seconds): A = Await.result(a, atMost)
  def withServer(server: Server)(test: Server => Unit) = try { test(server) } finally { server.stop() }
  def success[A](a: A) = Future.successful(a)
  def status(req: Request)(implicit e: ExecutionContext, ssl: SSL.Configuration): Int = {
    await(Client.run(req)(res => success(res.status)))
  }
  def contentString(req: Request)(implicit e: ExecutionContext, ssl: SSL.Configuration): String = {
    await(Client.run(req)(_.read[String]))
  }
  def getBytes(content: Content): Vector[Byte] = content.stream.runLog.unsafeRun()
  def bytes(data: Int*): Seq[Byte] = data.map(_.toByte)
  val timer = new Timer(true)
  def timeout[A](d: Duration, a: A): Future[A] = {
    val p = Promise[A]
    timer.schedule(new TimerTask { def run() = p.success(a) }, d.toMillis)
    p.future
  }
}
