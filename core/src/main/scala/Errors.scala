package lol.http

case class Panic(msg: String) extends RuntimeException(msg)
object Panic { def !!!(msg: String = "Unexpected behavior") = throw Panic(msg) }

case class Error(code: Int, msg: String) extends RuntimeException(msg)
object Error {
  val ConnectionClosed = Error(1, "Connection closed")
  val ClientAlreadyClosed = Error(2, "Client already closed")
  val StreamAlreadyConsumed = Error(3, "The content stream has already been consumed")
  val UpgradeRefused = Error(4, "Connection upgrade was denied by the server")
  val TooManyWaiters = Error(5, "Client has already too many waiting requests")
  val AutoRedirectNotSupported = Error(6, "Automatic redirects is only allowed for GET requests")
  val HostHeaderMissing = Error(7, "The Host header was missing in the request")
}
