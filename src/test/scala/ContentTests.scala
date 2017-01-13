package lol.http

import java.io.{ InputStream }

import scala.io.{ Codec }

class ContentTests extends Tests {

  test("Text encoding") {
    val textContent = Content("Héhé")
    textContent.contentType should be (Some("text/plain; charset=UTF-8"))
    getBytes(textContent) should contain theSameElementsInOrderAs "Héhé".getBytes("utf-8")

    val textContent2 = Content("Héhé")(ContentEncoder.text(Codec.ISO8859))
    textContent2.contentType should be (Some("text/plain; charset=ISO-8859-1"))
    getBytes(textContent2) should contain theSameElementsInOrderAs "Héhé".getBytes("iso8859-1")

    implicit val defaultTextEncoderHere = ContentEncoder.text(Codec("utf-16"))
    val textContent3 = Content("Héhé")
    textContent3.contentType should be (Some("text/plain; charset=UTF-16"))
    getBytes(textContent3) should contain theSameElementsInOrderAs "Héhé".getBytes("utf-16")
  }

  test("Text decoding") {
    val text = "Do you speak English? えいごをはなせますか"
    val textContent = Content(text)

    textContent.as[String].unsafeRun() should be (text)
    textContent.as(ContentDecoder.text(codec = Codec.ISO8859)).unsafeRun() should not be (text)
    textContent.as(ContentDecoder.text(maxSize = 21)).unsafeRun() should be (text.take(21))

    // A truncated byte buffer is not necessarely a valid utf-8 sequence
    a [java.nio.charset.MalformedInputException] should be thrownBy {
      textContent.as(ContentDecoder.text(maxSize = 23)).unsafeRun()
    }
  }

  test("UrlEncoded") {
    val form = Map(
      "Héhé" -> Seq("lol", "wat&hop"),
      "Do you speak English?" -> Seq("えいごをはなせますか")
    )
    val formContent = Content(form)
    formContent.contentType should be (Some("application/x-www-form-urlencoded"))
    new String(getBytes(formContent).toArray, "us-ascii") should be (
      "H%C3%A9h%C3%A9=lol&H%C3%A9h%C3%A9=wat%26hop&Do+you+speak+English%3F=%E3%81%88%E3%81%84%E3%81%94" +
      "%E3%82%92%E3%81%AF%E3%81%AA%E3%81%9B%E3%81%BE%E3%81%99%E3%81%8B"
    )
    formContent.as[Map[String,Seq[String]]].unsafeRun() should be (form)
    formContent.as[Map[String,String]].unsafeRun() should be (Map(
      "Héhé" -> "lol",
      "Do you speak English?" -> "えいごをはなせますか"
    ))

    val formWithCharset = Map(
      "_charset_" -> Seq("windows-1252"),
      "Héhé" -> Seq("lol", "wat&hop"),
      "Do you speak English?" -> Seq("えいごをはなせますか")
    )
    val formWithCharsetContent = Content(formWithCharset)
    formWithCharsetContent.contentType should be (Some("application/x-www-form-urlencoded"))
    new String(getBytes(formWithCharsetContent).toArray, "us-ascii") should be (
      "_charset_=windows-1252&H%E9h%E9=lol&H%E9h%E9=wat%26hop&Do+you+speak+English%3F=%26%2312360%3B%26%2312356%3B%26%2312372%3B%26%2312434%3B%26%2312399%3B%26%2312394%3B%26%2312379%3B%26%2312414%3B%26%2312377%3B%26%2312363%3B"
    )
    formWithCharsetContent.as[Map[String,Seq[String]]].unsafeRun() should be (formWithCharset)
  }

  test("in memory InputStream") {
    val someFile = this.getClass.getResourceAsStream("/lol.txt")
    someFile != null should be (true)

    val encoder = implicitly[ContentEncoder[InputStream]]
    val content = encoder(someFile)

    getBytes(content) should contain theSameElementsInOrderAs "LOL\n".getBytes("utf-8")
  }
}
