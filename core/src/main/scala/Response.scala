package lol.http

import cats.effect.{ IO }
import fs2.{ Stream }

/** An HTTP response.
  *
  * Represent all the data available in the HTTP response headers, and the response content that
  * can be consumed lazily if needed.
  *
  * A response can be used to upgrade the HTTP connection to a plain TCP connection. As a server,
  * when you create a response to accept the connection upgrade you must provide this function. It will
  * be called if the client decide to continue and to upgrade the connection. In this case you will
  * receive the upstream as parameter and you have to return the downstream.
  *
  * If, as a client you receive a 101 response, you can call this function by providing the upstream. In
  * return you will get the downstream.
  *
  * @param status the HTTP response code such as `200` or `404`.
  * @param content the response content.
  * @param headers the HTTP headers.
  * @param upgradeConnection a function that will be called to upgrade the connection to a plain TCP connection.
  */
case class Response(
  status: Int,
  content: Content = Content.empty,
  headers: Map[HttpString,HttpString] = Map.empty,
  upgradeConnection: (Stream[IO,Byte]) => Stream[IO,Byte] = _ => Stream.empty
) {

  /** Set the content of this response.
    * @param content the content to use for this response.
    * @param encoder the [[ContentEncoder]] to use to encode this content.
    * @return a copy of this response with a new content.
    */
  def apply[A](content: A)(implicit encoder: ContentEncoder[A]) = copy(content = Content.of(content))

  /** Consume the content attached to this response and eventually produces a value of type `A`.
    * @param decoder the [[ContentDecoder]] to use to read the content.
    * @return eventually a value of type `A`.
    */
  def readAs[A](implicit decoder: ContentDecoder[A]): IO[A] = content.as[A]

  /** Consume the content attached to this response if the status is in the Success 2xx range. Otherwise,
    * it consumes the response as String and report the error as a failed IO.
    * @param decoder the [[ContentDecoder]] to use to read the content.
    * @return eventually a value of type `A` or a failure if the status code was not 2xx.
    */
  def readSuccessAs[A](implicit decoder: ContentDecoder[A]): IO[A] = filterSuccess.flatMap(_ => readAs[A])

  /** Consume the content attached to this response by evaluating the provided effect function.
    * @param effect the function to use to consume the stream.
    * @return eventually a value of type `A`.
    */
  def read[A](effect: Stream[IO,Byte] => IO[A]): IO[A] = effect(content.stream)

  /** Consume the content attached to this response by evaluating the provided effect function the status is in
    * the Success 2xx range. Otherwise, it consumes the response as String and report the error as a failed IO.
    * @param effect the function to use to consume the stream.
    * @return eventually a value of type `A` or a failure if the status code was not 2xx.
    */
  def readSuccess[A](effect: Stream[IO,Byte] => IO[A]): IO[A] = filterSuccess.flatMap(_ => read(effect))

  private def filterSuccess: IO[Unit] =
    if(status >= 200 && status < 300) {
      IO.unit
    }
    else {
      readAs[String].
        flatMap { content =>
          IO.raiseError(Error.UnexpectedStatus(s"Expect success response, but got $status:\n$content"))
        }.
        attempt.flatMap {
          case Left(e) if e == Error.StreamAlreadyConsumed =>
            IO.raiseError(Error.UnexpectedStatus(s"Expect success response, but got $status"))
          case Left(e) =>
            IO.raiseError(e)
          case Right(_) =>
            Panic.!!!()
        }
    }

  /** Drain the content attached to this response. It is safe to call this operation even if the stream has
    * already been consumed.
    */
  def drain: IO[Unit] = read(_.handleErrorWith {
    case e: Throwable if e == Error.StreamAlreadyConsumed => Stream.empty
    case e: Throwable => Stream.raiseError(e)
  }.compile.drain)

  /** Return a successful unit IO if the response status is in the Success 2xx range.
    * Otherwise, it consumes the response as String and report the error as a failed IO.
    */
  def assertSuccess: IO[Unit] = filterSuccess

  /** Add some headers to this response.
    * @param headers the new headers to add.
    * @return a copy of the response with the new headers added.
    */
  def addHeaders(headers: Map[HttpString,HttpString]) = copy(headers = this.headers ++ headers)

  /** Add some headers to this response.
    * @param headers the new headers to add.
    * @return a copy of the response with the new headers added.
    */
  def addHeaders(headers: (HttpString,HttpString)*) = copy(headers = this.headers ++ headers.toMap)

  /** Remove some headers from this response.
    * @param headerNames the header names to remove
    * @return a copy of the response without the removed headers.
    */
  def removeHeaders(headerNames: HttpString*) = copy(headers = this.headers -- headerNames)

  /** @return true is this HTTP response is a redirect. */
  def isRedirect = status match {
    case 301 | 302 | 303 | 307 | 308 => true
    case _ => false
  }
}
