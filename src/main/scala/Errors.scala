package lol.http

case class Panic(msg: String) extends RuntimeException(s"Unexpected error, probably a bug. $msg")
object Panic { def !!!(msg: String = "") = throw Panic(msg) }

case class Error(msg: String) extends RuntimeException(msg)
object Error {
  val ConnectionClosed = Error("Connection closed")
  val ClientAlreadyClosed = Error("Client already closed")
  val UpgradeRefused = Error("Connection upgrade was denied by the server")
  val TooManyWaiters = Error("Client has already too many waiting requests")
  val AutoRedirectNotSupported = Error("Automatic redirects is only allowed for GET requests")
  val HostHeaderMissing = Error("The Host header was missing in the request")
}
