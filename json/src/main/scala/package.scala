package lol

import http._

import cats.effect.IO

import io.circe.{ Decoder }

/** Provides integration with the [[https://circe.github.io/circe/ circe]] JSON library.
  *
  * {{{
  * Server.listen(8888) { request =>
  *   request.readAs(json[MyRequestData]).flatMap { data =>
  *     Ok(MyResponseData(data).asJson)
  *   }
  * }
  * }}}
  *
  * Nothing really special here. Just a bunch of useful [[lol.http.ContentEncoder ContentEncoder]] and
  * [[lol.http.ContentDecoder ContentDecoder]] for `io.circe.Json` values.
  *
  * This module is optional and you can easily use another scala JSON library by providing the required
  * encoder/decoder (or treating JSON as string).
  */
package object json {

  /** Default encoder for JSON values, using `UTF-8` as charset. */
  implicit val defaultJsonEncoder = JsonContent.encoder()

  /** Default decoder for JSON values, using `UTF-8` as charset and [[lol.http.ContentDecoder.MaxSize MaxSize]] as
    * maximum amount of bytes to read in memory.
    **/
  implicit val defaultJsonDecoder = JsonContent.decoder()

  /** JSON support for Server Sent Events. */
  implicit val sseJsonEventEncoder = JsonContent.sseEventEncoder

  /** JSON support for Server Sent Events. */
  implicit val sseJsonEventDecoder = JsonContent.sseEventDecoder

  /** Creates a [[lol.http.ContentDecoder ContentDecoder]] for any type `A` given that there is an available
    * circe JSON decoder for `A`.
    * @param jsonDecoder the circe JSON decoder for type `A`.
    * @return a [[lol.http.ContentDecoder ContentDecoder]] for `A`.
    */
  def json[A](implicit jsonDecoder: Decoder[A]) = new ContentDecoder[A] {
    def apply(content: Content) = defaultJsonDecoder.apply(content).flatMap { json =>
      json.as[A].fold(IO.raiseError, IO.pure)
    }
  }

}
