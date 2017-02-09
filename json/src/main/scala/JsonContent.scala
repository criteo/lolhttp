package lol.json

import lol.http._

import fs2.{ Task }

import scala.io.{ Codec }

import io.circe.{ Json }
import io.circe.parser.{ parse }

object JsonContent {

  def encoder(codec: Codec = Codec.UTF8): ContentEncoder[Json] = new ContentEncoder[Json] {
    def apply(data: Json) = ContentEncoder.text()(data.toString).addHeaders(
      Headers.ContentType -> h"application/json; charset=$codec"
    )
  }

  def decoder(maxSize: Int = ContentDecoder.MaxSize, codec: Codec = Codec.UTF8) = new ContentDecoder[Json] {
    def apply(content: Content) = {
      ContentDecoder.text()(content).flatMap { text =>
        parse(text).fold(Task.fail, Task.now)
      }
    }
  }

}
