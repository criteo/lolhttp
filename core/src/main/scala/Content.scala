package lol.http

import scala.io.{ Codec }
import scala.util.{ Try }

import scala.concurrent.{ blocking, ExecutionContext }

import java.io.{ File, InputStream }
import java.net.{ URLDecoder, URLEncoder }
import java.nio.{ ByteBuffer, CharBuffer }
import java.nio.channels.{ AsynchronousFileChannel, CompletionHandler }
import java.nio.file.{ StandardOpenOption }

import fs2.{ Strategy, Chunk, Task, Stream }

case class Content(
  stream: Stream[Task,Byte],
  headers: Map[HttpString,HttpString] = Map.empty
) {
  def as[A](implicit decoder: ContentDecoder[A]): Task[A] = decoder(this)
  def addHeaders(newHeaders: (HttpString,HttpString) *) = copy(headers = headers ++ newHeaders.toMap)
}

object Content {
  val empty = Content(Stream.empty, Map(Headers.ContentLength -> h"0"))
  def of[A](a: A)(implicit encoder: ContentEncoder[A]): Content = encoder(a)
}

trait ContentDecoder[+A] { def apply(content: Content): Task[A] }
object ContentDecoder {
  val MaxSize = Try {
    System.getProperty("lol.http.ContentDecoder.maxSizeInMemory").toInt
  }.getOrElse(1024 * 1024)

  private val UsAscii = Codec("us-ascii")

  implicit val discard = new ContentDecoder[Unit] {
    def apply(content: Content) = content.stream.drain.run
  }

  def binary(maxSize: Int = MaxSize): ContentDecoder[Array[Byte]] = new ContentDecoder[Array[Byte]] {
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
  implicit val defaultBinary = binary(MaxSize)

  def text(maxSize: Int = MaxSize, codec: Codec = Codec.UTF8): ContentDecoder[String] = new ContentDecoder[String] {
    def apply(content: Content) = binary(maxSize)(content).map { bytes =>
      codec.decoder.decode(ByteBuffer.wrap(bytes)).toString
    }
  }
  implicit val defaultText = text(MaxSize, Codec.UTF8)

  // https://www.w3.org/TR/html5/forms.html#url-encoded-form-data
  def urlEncoded(maxSize: Int = MaxSize, codec: Codec = UsAscii): ContentDecoder[Map[String,Seq[String]]] = new ContentDecoder[Map[String,Seq[String]]] {
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
  implicit val defaultUrlEncoded = urlEncoded(MaxSize, UsAscii)

  def urlEncoded0(maxSize: Int = MaxSize, codec: Codec = UsAscii): ContentDecoder[Map[String,String]] = new ContentDecoder[Map[String,String]] {
    def apply(content: Content) = urlEncoded(maxSize, codec)(content).map { data =>
      data.mapValues(_.headOption).collect {
        case (key, Some(value)) => (key,value)
      }
    }
  }
  implicit val defaultUrlEncoded0 = urlEncoded0(MaxSize, UsAscii)
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
      stream = Stream.chunk(Chunk.bytes(data)),
      headers = Map(
        Headers.ContentLength -> HttpString(data.size),
        Headers.ContentType -> h"application/octet-stream"
      )
    )
  }

  implicit val byteBuffer = new ContentEncoder[ByteBuffer] {
    def apply(data: ByteBuffer) = {
      val bytes = Array.ofDim[Byte](data.remaining)
      data.get(bytes)
      Content(
        stream = Stream.chunk(Chunk.bytes(bytes)),
        headers = Map(
          Headers.ContentLength -> HttpString(bytes.size),
          Headers.ContentType -> h"application/octet-stream"
        )
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
      }.addHeaders(Headers.ContentType -> h"application/x-www-form-urlencoded")
    }
  }

  def text(codec: Codec = Codec.UTF8): ContentEncoder[CharSequence] = new ContentEncoder[CharSequence] {
    def apply(data: CharSequence) = byteBuffer(
      codec.encoder.encode(CharBuffer.wrap(data))
    ).addHeaders(Headers.ContentType -> h"text/plain; charset=$codec")
  }
  implicit val defaultText = text(Codec.UTF8)

  def inputStream(blockingExecutor: ExecutionContext, chunkSize: Int = 16 * 1024) = new ContentEncoder[InputStream] {
    def apply(data: InputStream) = {
      implicit val S = Strategy.fromExecutionContext(blockingExecutor)
      val stream = Stream.eval(Task.async[Option[Chunk[Byte]]] { cb =>
        try {
          blocking {
            val buffer = Array.ofDim[Byte](chunkSize)
            val read = data.read(buffer)
            if(read > -1) {
              cb(Right(Some(Chunk.bytes(buffer, 0, read))))
            }
            else {
              cb(Right(None))
            }
          }
        }
        catch {
          case e: Throwable => cb(Left(e))
        }
      }).repeat.takeWhile(_.isDefined).flatMap(c => Stream.chunk(c.get)).onFinalize(Task.delay {
        data.close()
      })

      Content(
        stream,
        headers = Map(Headers.ContentType -> h"application/octet-stream")
      )
    }
  }

  def file(chunkSize: Int = 16 * 1024)(implicit executor: ExecutionContext) = new ContentEncoder[File] {
    def apply(data: File) = {
      implicit val S = Strategy.fromExecutionContext(executor)
      val channel = AsynchronousFileChannel.open(data.toPath, StandardOpenOption.READ)
      val buffer = ByteBuffer.allocateDirect(chunkSize)
      var position = 0
      val stream = Stream.eval(Task.async[Option[Chunk[Byte]]] { cb =>
        try {
          if(channel.isOpen) {
            channel.read(buffer, position, (), new CompletionHandler[Integer,Unit] {
              def completed(read: Integer, a: Unit) = {
                val bytes = {
                  buffer.flip()
                  val x = Array.ofDim[Byte](buffer.remaining)
                  buffer.get(x)
                  x
                }
                if(read > -1) {
                  {
                    position = position + read
                    buffer.clear()
                  }
                  cb(Right(Some(Chunk.bytes(bytes))))
                }
                else {
                  cb(Right(None))
                }
              }
              def failed(e: Throwable, a: Unit) = {
                cb(Left(e))
              }
            })
          }
          else {
            cb(Right(None))
          }
        }
        catch {
          case e: Throwable => cb(Left(e))
        }
      }).repeat.takeWhile(_.isDefined).flatMap(c => Stream.chunk(c.get)).onFinalize(Task.delay {
        channel.close()
      })

      Content(
        stream,
        headers = Map(
          Headers.ContentLength -> HttpString(data.length),
          Headers.ContentType -> HttpString(internal.guessContentType(data.getName))
        )
      )
    }
  }
  implicit def defaultFile(implicit executor: ExecutionContext) = file()

}

case class ClasspathResource(path: String) {
  def inputStream = Option(this.getClass.getResourceAsStream(path))
  def exists = inputStream.isDefined
}
object ClasspathResource {
  implicit def encoder(implicit executor: ExecutionContext) = new ContentEncoder[ClasspathResource] {
    def apply(data: ClasspathResource) = {
      data.inputStream.fold(Content(Stream.fail(Error.ClasspathResourceMissing))) { is =>
        ContentEncoder.inputStream(executor)(is)
      }
    }
  }
}
