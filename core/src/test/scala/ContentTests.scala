package lol.http

import java.io.{ File }
import scala.io.{ Source, Codec }

import fs2.{ Stream }

class ContentTests extends Tests {

  test("Text encoding", Pure) {
    val textContent = Content.of("Héhé")
    textContent.headers.get(Headers.ContentType) should be (Some(h"text/plain; charset=UTF-8"))
    getBytes(textContent) should contain theSameElementsInOrderAs "Héhé".getBytes("utf-8")

    val textContent2 = Content.of("Héhé")(ContentEncoder.text(Codec.ISO8859))
    textContent2.headers.get(Headers.ContentType) should be (Some(h"text/plain; charset=ISO-8859-1"))
    getBytes(textContent2) should contain theSameElementsInOrderAs "Héhé".getBytes("iso8859-1")

    implicit val defaultTextEncoderHere = ContentEncoder.text(Codec("utf-16"))
    val textContent3 = Content.of("Héhé")
    textContent3.headers.get(Headers.ContentType) should be (Some(h"text/plain; charset=UTF-16"))
    getBytes(textContent3) should contain theSameElementsInOrderAs "Héhé".getBytes("utf-16")

    val nullTextContent = Content.of(null)(ContentEncoder.text(Codec.UTF8))
    nullTextContent.headers.get(Headers.ContentType) should be (Some(h"text/plain; charset=UTF-8"))
    getBytes(nullTextContent) should have size 0
  }

  test("Text decoding", Pure) {
    val text = "Do you speak English? えいごをはなせますか"
    val textContent = Content.of(text)

    textContent.as[String].unsafeRunSync() should be (text)
    textContent.as(ContentDecoder.text(defaultCodec = Codec.ISO8859)).unsafeRunSync() should not be (text)
    textContent.as(ContentDecoder.text(maxSize = 21)).unsafeRunSync() should be (text.take(21))

    // A truncated byte buffer is not necessarely a valid utf-8 sequence
    a [java.nio.charset.MalformedInputException] should be thrownBy {
      textContent.as(ContentDecoder.text(maxSize = 23)).unsafeRunSync()
    }
  }

  test("UrlEncoded", Pure) {
    val form = Map(
      "Héhé" -> Seq("lol", "wat&hop"),
      "Do you speak English?" -> Seq("えいごをはなせますか")
    )
    val formContent = Content.of(form)
    formContent.headers.get(Headers.ContentType) should be (Some("application/x-www-form-urlencoded"))
    new String(getBytes(formContent).toArray, "us-ascii") should be (
      "H%C3%A9h%C3%A9=lol&H%C3%A9h%C3%A9=wat%26hop&Do+you+speak+English%3F=%E3%81%88%E3%81%84%E3%81%94" +
      "%E3%82%92%E3%81%AF%E3%81%AA%E3%81%9B%E3%81%BE%E3%81%99%E3%81%8B"
    )
    formContent.as[Map[String,Seq[String]]].unsafeRunSync() should be (form)
    formContent.as[Map[String,String]].unsafeRunSync() should be (Map(
      "Héhé" -> "lol",
      "Do you speak English?" -> "えいごをはなせますか"
    ))

    val formWithCharset = Map(
      "_charset_" -> Seq("windows-1252"),
      "Héhé" -> Seq("lol", "wat&hop"),
      "Do you speak English?" -> Seq("えいごをはなせますか")
    )
    val formWithCharsetContent = Content.of(formWithCharset)
    formWithCharsetContent.headers.get(Headers.ContentType) should be (Some("application/x-www-form-urlencoded"))
    new String(getBytes(formWithCharsetContent).toArray, "us-ascii") should be (
      "_charset_=windows-1252&H%E9h%E9=lol&H%E9h%E9=wat%26hop&Do+you+speak+English%3F=%26%2312360%3B%26%2312356%3B%26%2312372%3B%26%2312434%3B%26%2312399%3B%26%2312394%3B%26%2312379%3B%26%2312414%3B%26%2312377%3B%26%2312363%3B"
    )
    formWithCharsetContent.as[Map[String,Seq[String]]].unsafeRunSync() should be (formWithCharset)
  }

  test("InputStream", Pure) {
    def a = this.getClass.getResourceAsStream("/lol.txt")
    def b = this.getClass.getResourceAsStream("/META-INF/INDEX.LIST")

    a != null should be (true)
    b != null should be (true)

    val bRealContent = Source.fromInputStream(b)(Codec.UTF8).mkString

    getBytes(ContentEncoder.inputStream().apply(a)) should contain theSameElementsInOrderAs "LOL\n".getBytes("utf-8")
    getString(ContentEncoder.inputStream().apply(b)) should be (bRealContent)

    ContentEncoder.inputStream(chunkSize = 1).apply(a).stream.chunks.map(c => new String(c.toArray)).
      interleave(Stream("_").repeat).
      compile.toVector.unsafeRunSync().mkString should be ("L_O_L_\n_")

    ContentEncoder.inputStream(chunkSize = 2).apply(a).stream.chunks.map(c => new String(c.toArray)).
      interleave(Stream("_").repeat).
      compile.toVector.unsafeRunSync().mkString should be ("LO_L\n_")
  }

  test("File") {

    val url = this.getClass.getResource("/index.html")
    url != null should be (true)
    url.toString.startsWith("file:") should be (true)

    val file = new File(url.toString.drop(5))
    file.exists should be (true)

    val realContent = Source.fromFile(file)(Codec.UTF8).mkString

    val content = implicitly[ContentEncoder[java.io.File]].apply(file)

    content.headers should be (Map(
      Headers.ContentLength -> h"59",
      Headers.ContentType -> h"text/html"
    ))

    getString(content) should be (realContent)

    val content2 = ContentEncoder.file(1).apply(file)

    content2.headers should be (Map(
      Headers.ContentLength -> h"59",
      Headers.ContentType -> h"text/html"
    ))

    val x = content2.stream.chunks.map(c => new String(c.toArray)).
      interleave(Stream("_").repeat).
      compile.toVector.unsafeRunSync().mkString

    x should be (realContent.zip(0 to realContent.size map(_ => '_')).map { case (a,b) => "" + a + b }.mkString)
  }

  test("Classpath resources") {
    import Headers._
    import scala.concurrent.ExecutionContext.Implicits.global

    ClasspathResource("/lol.txt").exists should be (true)
    ClasspathResource("/toto/../lol.txt").exists should be (false)
    ClasspathResource("/lol/http/Server.class").exists should be (true)
    ClasspathResource("/lol/http/internal/../Server.class").exists should be (false)

    withServer(Server.listen() {
      case GET at "/broken" =>
        Ok(ClasspathResource("/oops"))
      case GET at url"/$file" =>
        val resource = ClasspathResource(s"/$file")
        if(resource.exists) Ok(resource) else NotFound(s"$file not found")
    }) { server =>
      val url = s"http://localhost:${server.port}"

      headers(Get(s"$url/lol.txt")) should be (Map(TransferEncoding -> "chunked", ContentType -> "text/plain"))

      status(Get(s"$url/lol.txt")) should be (200)
      contentString(Get(s"$url/lol.txt")) should be ("LOL\n")

      status(Get(s"$url/bam.png")) should be (404)
      contentString(Get(s"$url/bam.png")) should be ("bam.png not found")

      contentString(Get(s"$url/broken")) shouldBe empty
    }
  }
}
