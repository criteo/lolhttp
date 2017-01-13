package lol.http

import scala.io.{ Codec }
import scala.util.{ Try }

import java.io.{ InputStream }
import java.net.{ URLDecoder, URLEncoder }
import java.nio.{ ByteBuffer, CharBuffer }

import fs2.{ Chunk, Task, Stream }

case class Content(
  stream: Stream[Task,Byte],
  contentType: Option[String] = None,
  length: Option[Long] = None
) {
  def as[A](implicit decoder: ContentDecoder[A]): Task[A] = decoder(this)
}

object Content {
  val empty = Content(Stream.empty, None, Some(0))
  def apply[A](a: A)(implicit encoder: ContentEncoder[A]): Content = encoder(a)
}

trait ContentDecoder[+A] { def apply(content: Content): Task[A] }
object ContentDecoder {
  private val MAX_SIZE = Try {
    System.getProperty("lol.http.ContentDecoder.maxSizeInMemory").toInt
  }.getOrElse(1024 * 1024)

  private val US_ASCII = Codec("us-ascii")

  implicit val discard = new ContentDecoder[Unit] {
    def apply(content: Content) = content.stream.drain.run
  }

  def binary(maxSize: Int = MAX_SIZE): ContentDecoder[Array[Byte]] = new ContentDecoder[Array[Byte]] {
    def apply(content: Content) = content.stream.take(maxSize).chunks.runLog.map { arrays =>
      val totalSize = arrays.foldLeft(0)(_ + _.size)
      val result = Array.ofDim[Byte](totalSize)
      arrays.foldLeft(0) {
        case (offset, array) =>
          System.arraycopy(array.toArray, 0, result, offset, array.size)
          (offset + array.size)
      }
      result
    }
  }
  implicit val defaultBinary = binary(MAX_SIZE)

  def text(maxSize: Int = MAX_SIZE, codec: Codec = Codec.UTF8): ContentDecoder[String] = new ContentDecoder[String] {
    def apply(content: Content) = binary(maxSize)(content).map { bytes =>
      codec.decoder.decode(ByteBuffer.wrap(bytes)).toString
    }
  }
  implicit val defaultText = text(MAX_SIZE, Codec.UTF8)

  // https://www.w3.org/TR/html5/forms.html#url-encoded-form-data
  def urlEncoded(maxSize: Int = MAX_SIZE, codec: Codec = US_ASCII): ContentDecoder[Map[String,Seq[String]]] = new ContentDecoder[Map[String,Seq[String]]] {
    val ENTITY = """[&][#](\d+)[;]""".r
    def apply(content: Content) = text(maxSize, codec)(content).map { text =>
      def decode1(str: String) = URLDecoder.decode(str, "iso8859-1")
      val pairs = text.split("[&]").toList.
        map {
          case "" => ("", "")
          case string if string.head == '=' => ("", string)
          case string if string.last == '=' => (string, "")
          case string if string.indexOf("=") == -1 => (string, string)
          case string =>
            val name :: rest = string.split("[=]").toList
            (name, rest.mkString("="))
        }.
        map { case (name, value) =>
          (decode1(name), decode1(value))
        }
      val charset = pairs.find(_._1 == "_charset_").map(_._2).getOrElse("utf-8")
      def decode2(str: String) = ENTITY.replaceAllIn(
        new String(str.getBytes("iso8859-1"), charset),
        _.group(1).toInt.asInstanceOf[Char].toString
      )
      val decodedPairs = pairs.map { case (name, value) =>
        (decode2(name), decode2(value))
      }
      decodedPairs.groupBy(_._1).map {
        case (name, x) => (name, x.map(_._2).toSeq)
      }
    }
  }
  implicit val defaultUrlEncoded = urlEncoded(MAX_SIZE, US_ASCII)

  def urlEncoded0(maxSize: Int = MAX_SIZE, codec: Codec = US_ASCII): ContentDecoder[Map[String,String]] = new ContentDecoder[Map[String,String]] {
    def apply(content: Content) = urlEncoded(maxSize, codec)(content).map { data =>
      data.mapValues(_.headOption).collect {
        case (key, Some(value)) => (key,value)
      }
    }
  }
  implicit val defaultUrlEncoded0 = urlEncoded0(MAX_SIZE, US_ASCII)
}

trait ContentEncoder[-A] { def apply(a: A): Content }
object ContentEncoder {
  implicit val emptyContent = new ContentEncoder[Unit] {
    def apply(empty: Unit) = Content.empty
  }

  implicit val identity = new ContentEncoder[Content] {
    def apply(data: Content) = data
  }

  implicit val binary = new ContentEncoder[Array[Byte]] {
    def apply(data: Array[Byte]) = Content(
      contentType = Some("application/octet-stream"),
      length = Some(data.size),
      stream = Stream.chunk(Chunk.bytes(data))
    )
  }

  implicit val byteBuffer = new ContentEncoder[ByteBuffer] {
    def apply(data: ByteBuffer) = {
      val bytes = Array.ofDim[Byte](data.remaining)
      data.get(bytes)
      Content(
        contentType = Some("application/octet-stream"),
        length = Some(bytes.size),
        stream = Stream.chunk(Chunk.bytes(bytes))
      )
    }
  }

  // https://www.w3.org/TR/html5/forms.html#url-encoded-form-data
  implicit val urlEncoded = new ContentEncoder[Map[String,Seq[String]]] {
    def apply(data: Map[String,Seq[String]]) = {
      val charset = Codec(data.get("_charset_").flatMap(_.headOption).getOrElse("utf-8"))
      val (isUnicode, encoder) = (charset.name.startsWith("utf-"), charset.encoder)
      def encode(str: String) = URLEncoder.encode(if(isUnicode) str else str.flatMap { c =>
        if(encoder.canEncode(c)) c.toString else s"&#${c.toInt};"
      }, charset.name)
      text(Codec("us-ascii")) {
        data.flatMap { case (key, values) => values.map { case value =>
          s"${encode(key)}=${encode(value)}"
        }}.mkString("&")
      }.copy(contentType = Some("application/x-www-form-urlencoded"))
    }
  }

  def text(codec: Codec = Codec.UTF8): ContentEncoder[CharSequence] = new ContentEncoder[CharSequence] {
    def apply(data: CharSequence) = byteBuffer(
      codec.encoder.encode(CharBuffer.wrap(data))
    ).copy(contentType = Some(s"text/plain; charset=$codec"))
  }
  implicit val defaultText = text(Codec.UTF8)

  // Broken implementation
  implicit val inputStream = new ContentEncoder[InputStream] {
    def apply(data: InputStream) = {
      Content(
        contentType = Some("application/octet-stream"),
        length = Some(data.available),
        stream = {
          val bytes = Array.ofDim[Byte](data.available)
          data.read(bytes)
          Stream.chunk(Chunk.bytes(bytes))
        }
      )
    }
  }
}
