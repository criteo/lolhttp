package lol.http

import Headers._

import scala.io.{ Codec }
import scala.util.{ Try }

import java.io.{ File, InputStream }
import java.nio.{ ByteBuffer, CharBuffer }
import java.nio.channels.{ AsynchronousFileChannel, CompletionHandler }
import java.nio.file.{ StandardOpenOption }

import cats.effect.{ IO }
import fs2.{ Chunk, Stream }

/** An HTTP message content body.
  *
  * It is used to represent the content body for both HTTP requests & responses. It is composed
  * of a lazy stream of byte that can be consumed if needed, and a set of content-related HTTP headers
  * (such as `Content-Length`, `Content-Type`, etc.).
  *
  * The provided stream is not pure and can only be consumed once.
  * @param stream an [[fs2.Stream]] of `Byte`.
  * @param headers a set of content-related HTTP headers.
  */
case class Content(
  stream: Stream[IO,Byte],
  headers: Map[HttpString,HttpString] = Map.empty
) {

  /** Create an [[cats.effect.IO]] that consumes this stream to a value of type `A`.
    * @param decoder the [[lol.http.ContentDecoder]] is able to read stream as values of type `A`.
    * @return an [[cats.effect.IO]] that can be run to consume the stream.
    */
  def as[A](implicit decoder: ContentDecoder[A]): IO[A] = decoder(this)

  /** Add new HTTP headers to this content.
    * @param newHeaders the set of new HTTP header names and values to add to this content.
    * @return a copy of the content with the new headers added.
    */
  def addHeaders(newHeaders: (HttpString,HttpString)*) = copy(headers = headers ++ newHeaders.toMap)

  /** Remove HTTP headers from this content.
    * @param headerNames the set of HTTP headers names to remove fromt this content.
    * @return a copy of the content without the removed headers.
    */
  def removeHeaders(headerNames: HttpString*) = copy(headers = headers -- headerNames)
}

/** Build HTTP message content body.
  *
  * {{{
  * val textContent: Content = Content.of("Hello world!")
  * }}}
  *
  * Given an existing [[ContentDecoder]] for a type `A`, this object allows to create
  * HTTP content from `A` values. Meaning, it will encode the value into a stream of bytes, and a
  * set of appropriate HTTP headers.
  */
object Content {

  /** An empty HTTP content body (empty stream and `Content-Length: 0` header). */
  val empty = Content(Stream.empty, Map(ContentLength -> h"0"))

  /** Create a new content body from value a.
    * @param value a scala value that we want to transform into an HTTP content.
    * @param encoder a [[ContentDecoder]] that knows how to encode values of type `A`.
    * @return an HTTP content ready to be consumed or sent over the network.
    */
  def of[A](value: A)(implicit encoder: ContentEncoder[A]): Content = encoder(value)
}

/** An HTTP content decoder.
  *
  * {{{
  * val textContent: Content = ???
  * val textDecoder: ContentDecoder[String] = ContentDecoder.text(maxSize = 1024, defaultCodec = Codec.UTF8)
  * val text: String = textDecoder(textContent).unsafeRun()
  * }}}
  *
  * A content decoder is able to parse an HTTP content into a scala value of type `A`. It will look
  * at the content HTTP headers if needed, and consume the content stream bytes to eventually output
  * a scala value.
  */
trait ContentDecoder[+A] {

  /** Create an [[cats.effect.IO]] that consumes the content stream and produces a scala value.
    * @param content an HTTP content.
    * @return an [[cats.effect.IO]] that you can run to eventually retrieve the scala value.
    */
  def apply(content: Content): IO[A]
}

/** Library of built-in content decoders.
  *
  * This provides content decoder functions for the common scala types, and implicit decoder
  * configured with a set of sensible default.
  *
  * The implicitly provided decoders are chosen by the compiler and cannot be explicitly configured. In
  * particular they are automatically configured with a [[MaxSize]] limit that specify the maximum amount
  * of bytes they are authorized to read in memory.
  *
  * It means that for example, this code:
  *
  * {{{
  * val str: String = request.readAs[String]
  * }}}
  *
  * will truncate the content body if it is bigger than the [[MaxSize]] property. The default for [[MaxSize]] is
  * `1MB`, and can be configured globally via the `lol.http.ContentDecoder.maxSizeInMemory` system property.
  *
  * If you want to configure the content decoder to allow it to read more data despite the maximum set in [[MaxSize]],
  * you can just pass the content decoder yourself instead of relying on implicit resolution:
  *
  * {{{
  * val str: String = request.readAs(text(maxSize = 10 * 1024 * 1024))
  * }}}
  */
object ContentDecoder {

  /** Default configuration for the maximum amount of bytes a decoder can read in memory.
    * Default to `1MB`, and can be configured globally via `lol.http.ContentDecoder.maxSizeInMemory`
    * system property.
    */
  val MaxSize = Try {
    System.getProperty("lol.http.ContentDecoder.maxSizeInMemory").toInt
  }.getOrElse(1024 * 1024)

  private val UsAscii = Codec("us-ascii")

  /** A content decoder that discard everything from the stream and returns `Unit`. */
  implicit val discard = new ContentDecoder[Unit] {
    def apply(content: Content) = content.stream.compile.drain
  }

  /** Create binary content decoders. They read the byte stream in memory and return them as an `Array[Byte]`.
    * @param maxSize the maximum amount of bytes that can read in memory.
    * @return a content decoder for `Array[Byte]`.
    */
  def binary(maxSize: Int = MaxSize): ContentDecoder[Array[Byte]] = new ContentDecoder[Array[Byte]] {
    def apply(content: Content) = content.stream.take(maxSize).chunks.compile.toVector.map { arrays =>
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

  /** Default binary content decoder configured with `maxSize` equals to [[MaxSize]]. */
  implicit val defaultBinary = binary(MaxSize)

  /** Create text content decoders. They read the byte stream in memory and return them as a `String`.
    * The decoder looks for the charset to use in the `Content-Type` HTTP header, or otherwise fallback to the
    * provided default codec.
    * @param maxSize the maximum amount of bytes that can read in memory.
    * @param defaultCodec the default codec to use to decode the bytes as text if not specified in the `Content-Type` HTTP header.
    * @return a content decoder for `Array[Byte]`.
    */
  def text(maxSize: Int = MaxSize, defaultCodec: Codec = Codec.UTF8): ContentDecoder[String] = new ContentDecoder[String] {
    def apply(content: Content) = binary(maxSize)(content).map { bytes =>
      defaultCodec.decoder.decode(ByteBuffer.wrap(bytes)).toString
    }
  }

  /** Default text content decoder configured with `maxSize` equals to [[MaxSize]] and `defaultCodec` to UTF-8. */
  implicit val defaultText = text(MaxSize, Codec.UTF8)

  /** Create content decoder for `url-encoded-form-data`. The content is converted to a `Map[String,Seq[String]]`.
    * See [[https://www.w3.org/TR/html5/forms.html#url-encoded-form-data]].
    * @param maxSize the maximum amount of bytes that can read in memory.
    * @param codec the codec to use to read the content (should be us-ascii as defined in the specification).
    * @return a content decoder for `Map[String,Seq[String]]`.
    */
  def urlEncoded(maxSize: Int = MaxSize, codec: Codec = UsAscii): ContentDecoder[Map[String,Seq[String]]] = new ContentDecoder[Map[String,Seq[String]]] {
    def apply(content: Content) = text(maxSize, codec)(content).map(internal.Url.parseQueryString).map(_.groupBy(_._1).map {
      case (name, x) => (name, x.map(_._2).toSeq)
    })
  }

  /** Default `url-encoded-form-data` decoder configured with `maxSize` equals to [[MaxSize]]. */
  implicit val defaultUrlEncoded = urlEncoded(MaxSize, UsAscii)

  /** Same as [[urlEncoded]] but keeps only one value per key, therefore producing a `Map[String,String]` value. */
  def urlEncoded0(maxSize: Int = MaxSize, codec: Codec = UsAscii): ContentDecoder[Map[String,String]] = new ContentDecoder[Map[String,String]] {
    def apply(content: Content) = urlEncoded(maxSize, codec)(content).map { data =>
      data.mapValues(_.headOption).collect {
        case (key, Some(value)) => (key,value)
      }
    }
  }

  /** Default `url-encoded-form-data` decoder for `Map[String,String]`. */
  implicit val defaultUrlEncoded0 = urlEncoded0(MaxSize, UsAscii)
}

/** A HTTP content encoder.
  *
  * {{{
  * val text = "Hello, world"
  * val textEncoder: ContentEncoder[String] = ContentEncoder.text(codec = Codec.UTF8)
  * val textContent: Content = textEncoder(text)
  * }}}
  *
  * A content decoder is able to encode a scala value into an HTTP content . It will produce both
  * a stream of bytes and the set of required HTTP headers like `Content-Length` or `Content-Type`.
  */
trait ContentEncoder[-A] {

  /** Create a [[Content]] for the provided scala value.
    * @param value any scala value that encoded as an HTTP content.
    * @return an HTTP content.
    */
  def apply(value: A): Content
}

/** Library of built-in content encoders.
  *
  * This provides content encoder functions for the common scala types, and implicit encoder
  * configured with a set of sensible default.
  *
  * The implicitly provided encoders are chosen by the compiler and cannot be explicitly configured.
  *
  * For example, this code:
  *
  * {{{
  * val response = Ok("Hello, world!")
  * }}}
  *
  * will generate an HTTP response body by encoding the provided string using the UTF-8 charset.
  *
  * If you want to configure the content encoder, you can just pass it yourself instead of relying on
  * implicit resolution:
  *
  * {{{
  * val response = Ok("Hello, world!")(text(codec = Codec("us-ascii")))
  * }}}
  */
object ContentEncoder {

  /** Encode `Unit` values as an empty content. */
  implicit val emptyContent = new ContentEncoder[Unit] {
    def apply(empty: Unit) = Content.empty
  }

  /** Pass-through encoder for [[Content]] values. */
  implicit val identity = new ContentEncoder[Content] {
    def apply(data: Content) = data
  }

  /** Encode `Array[Byte]` binary data. */
  implicit val binary = new ContentEncoder[Array[Byte]] {
    def apply(data: Array[Byte]) = Content(
      stream = Stream.chunk(Chunk.bytes(data)),
      headers = Map(
        ContentLength -> HttpString(data.size),
        ContentType -> h"application/octet-stream"
      )
    )
  }

  /** Encode `ByteBuffer` binary data. */
  implicit val byteBuffer = new ContentEncoder[ByteBuffer] {
    def apply(data: ByteBuffer) = {
      val bytes = Array.ofDim[Byte](data.remaining)
      data.get(bytes)
      Content(
        stream = Stream.chunk(Chunk.bytes(bytes)),
        headers = Map(
          ContentLength -> HttpString(bytes.size),
          ContentType -> h"application/octet-stream"
        )
      )
    }
  }

  /** Encode `url-encoded-form-data` from a `Map[String,Seq[String]]` value.
    * See [[https://www.w3.org/TR/html5/forms.html#url-encoded-form-data]].
    */
  implicit val urlEncoded = new ContentEncoder[Map[String,Seq[String]]] {
    def apply(data: Map[String,Seq[String]]) = {
      text(Codec("us-ascii"))(internal.Url.toQueryString(data)).
        addHeaders(ContentType -> h"application/x-www-form-urlencoded")
    }
  }

  /** Create text content encoder using the provided charset.
    * @param codec the codec to use to code the string as binary.
    * @return an encoder for `java.lang.Charsequence`.
    */
  def text(codec: Codec = Codec.UTF8): ContentEncoder[CharSequence] = new ContentEncoder[CharSequence] {
    def apply(data: CharSequence) = {
      val charBuffer = Option(data)
        .map(CharBuffer.wrap)
        .getOrElse(CharBuffer.allocate(0))
      byteBuffer(
        codec.encoder.encode(charBuffer)
      ).addHeaders(ContentType -> h"text/plain; charset=$codec")
    }
  }

  /** Default text encoder, using `UTF-8` as charset. */
  implicit val defaultText = text(Codec.UTF8)

  /** Create a content encoder for java blocking `java.io.InputStream`.
    *
    * Note that the inputStream is read lazily and is not buffered in memory. Therefore the content length is not known
    * and no corresponding HTTP header is produced.
    *
    * @param chunkSize the size of chunks that will be produced in the content stream. Default to `16KB`.
    * @return an encoder for `java.io.InputStream`.
    */
  def inputStream(chunkSize: Int = 16 * 1024) = new ContentEncoder[InputStream] {
    def apply(data: InputStream) = {
      val stream = Stream.eval(IO.async[Option[Chunk[Byte]]] { cb =>
        try {
          val buffer = Array.ofDim[Byte](chunkSize)
          val read = data.read(buffer)
          if(read > -1) {
            cb(Right(Some(Chunk.bytes(buffer, 0, read))))
          }
          else {
            cb(Right(None))
          }
        }
        catch {
          case e: Throwable => cb(Left(e))
        }
      }).repeat.takeWhile(_.isDefined).flatMap(c => Stream.chunk(c.get)).onFinalize(IO {
        data.close()
      })

      Content(
        stream,
        headers = Map(ContentType -> h"application/octet-stream")
      )
    }
  }

  /** Create a content encoder for `java.io.File`.
    * @param chunkSize the size of chunks that will be produced in the content stream. Default to `16KB`.
    * @param executor an execution context that will be used to run the async IO operations.
    * @return an encoder for `java.io.File`.
    */
  def file(chunkSize: Int = 16 * 1024) = new ContentEncoder[File] {
    def apply(data: File) = {
      val channel = AsynchronousFileChannel.open(data.toPath, StandardOpenOption.READ)
      val buffer = ByteBuffer.allocateDirect(chunkSize)
      var position = 0
      val stream = Stream.eval(IO.async[Option[Chunk[Byte]]] { cb =>
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
      }).repeat.takeWhile(_.isDefined).flatMap(c => Stream.chunk(c.get)).onFinalize(IO {
        channel.close()
      })

      Content(
        stream,
        headers = Map(
          ContentLength -> HttpString(data.length),
          ContentType -> HttpString(internal.guessContentType(data.getName))
        )
      )
    }
  }

  /** Default text encoder, using `16KB` chunks. */
  implicit def defaultFile = file()

}

/** A resource than can be read from the classpath.
  *
  * {{{
  * Ok(ClasspathResource("/public/index.html"))
  * }}}
  *
  * A common use case is to serve static resources (html, js, images files) from the classpath.
  * [[ClasspathResource]] values can be directly encoded as [[Content]] and used to feed an HTTP response.
  *
  * Trying to write a missing resource on an HTTP response will close the connection.
  *
  * Also, this provide a basic security by requiring the path to be asbolutely defined. No directory navigation
  * is allowed. For example `ClasspathResource("/public/../public/index.html")` will resolve to a missing resource.
  */
case class ClasspathResource(path: String) {
  private val securedPath = {
    Option(this.getClass.getResource(path)).map(_.getPath).filter(_.contains(path)).map(_ => path)
  }

  /** @return maybe the `java.io.InputStream` for the resource if it exists in the classpath. */
  def inputStream = securedPath.flatMap(path => Option(this.getClass.getResourceAsStream(path)))

  /** @return true is the resource exists in the classpath. */
  def exists = inputStream.isDefined

  /** Returns the result of applying f to this resource if it exists. Otherwise evaluates the
    * `ifMissing` expression.
    * @param ifMissing the value to be return if the resource is missing.
    * @param f the function to apply to the resource if it exists.
    * @return a value of type `A`.
    */
  def fold[A](ifMissing: => A)(f: (ClasspathResource) => A) = inputStream.fold(ifMissing)(_ => f(this))
}

/** Define the implicit encoder for ClasspathResource. */
object ClasspathResource {

  /** A [[ContentEncoder]] for a [[ClasspathResource]].
    * Produces a failing stream if the resource is missing.
    */
  implicit val encoder = new ContentEncoder[ClasspathResource] {
    def apply(data: ClasspathResource) = {
      data.inputStream.fold(Content(Stream.raiseError(Error.ClasspathResourceMissing))) { is =>
        ContentEncoder.inputStream()(is).addHeaders(
          ContentType -> HttpString(internal.guessContentType(data.path)),
          TransferEncoding -> h"chunked"
        )
      }
    }
  }
}
