package lol

import http._

import fs2.{ Task }

import io.circe.{ Decoder }

package object json {

  implicit val defaultJsonEncoder = JsonContent.encoder()
  implicit val defaultJsonDecoder = JsonContent.decoder()

  def json[A: Decoder] = new ContentDecoder[A] {
    def apply(content: Content) = defaultJsonDecoder(content).flatMap { json =>
      json.as[A].fold(Task.fail, Task.now)
    }
  }

}
