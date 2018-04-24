package lol.http.internal

import scala.util.{ Try }
import scala.io.{ Codec }
import scala.util.matching.{ Regex }

import java.util.regex.{ Pattern }
import java.net.{ URLDecoder, URLEncoder }

import lol.http._

private[http] object Url {
  val ENTITY = """[&][#](\d+)[;]""".r

  def parseQueryString(qs: String): List[(String,String)] = {
    def decode1(str: String) = URLDecoder.decode(str, "iso8859-1")
    val pairs = qs.split("[&]").toList.
      map {
        case "" => ("", "")
        case string if string.head == '=' => ("", string.drop(1))
        case string if string.last == '=' => (string.dropRight(1), "")
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
    pairs.map { case (name, value) =>
      (decode2(name), decode2(value))
    }
  }

  def toQueryString(data: Map[String,Seq[String]]) = {
    val charset = Codec(data.get("_charset_").flatMap(_.headOption).getOrElse("utf-8"))
    val (isUnicode, encoder) = (charset.name.startsWith("utf-"), charset.encoder)
    def encode(str: String) = URLEncoder.encode(if(isUnicode) str else str.flatMap { c =>
      if(encoder.canEncode(c)) c.toString else s"&#${c.toInt};"
    }, charset.name)
    data.flatMap { case (key, values) => values.map { case value =>
      s"${encode(key)}=${encode(value)}"
    }}.mkString("&")
  }

  case class Matcher(ctx: StringContext) {
    def apply(args: Any*): String = ctx.s(args:_*)

    val (pathPattern, paramPatterns) = {
      ctx.parts.mkString("\u0000").split("[?]").toList match {
        case p :: rest =>
          val path = if(p.endsWith("\u0000...")) (p.dropRight(4) :+ '\u0001') else p
          val pathRegex = {
            path.
              filterNot(_ == '\u0001').
              split("[\u0000]").
              filterNot(_.isEmpty).
              map(Pattern.quote).
              mkString("([^/]*)") +
              (if(path.endsWith("\u0000")) "([^/]*)" else "") +
              (if(path.endsWith("\u0001")) "(.*)" else "")
          }.r
          val paramsRegexes = (rest match  {
            case Nil =>
              List.empty[(String,Regex)]
            case queryString :: Nil =>
              parseQueryString(queryString).map {
                case (key, value) =>
                  (key, ("^" + value.replaceAll("\u0000", "(.*)") + "$").r)
              }
            case _ =>
              throw Error.InvalidUrlMatcher("Several ? characters found in pattern.")
          })
          (pathRegex, paramsRegexes)
        case Nil =>
          Panic.!!!()
      }
    }

    def unapplySeq(req: Request): Option[Seq[String]] = unapplySeq(req.url)
    def unapplySeq(url: String): Option[Seq[String]] = {
      url.split("[?]").toList match {
        case path :: queryString =>
          pathPattern.unapplySeq(path).flatMap { pathParams =>
            Try {
              val params = parseQueryString(queryString.mkString("?")).groupBy(_._1).map {
                case (name, x) => (name, x.map(_._2).toSeq)
              }
              pathParams ++ paramPatterns.flatMap {
                case (param, pattern) =>
                  val values = params.get(param).getOrElse(Nil)
                  if(values.size > 1) sys.error("")
                  pattern.unapplySeq(values.headOption.getOrElse("\u0000")).get.map(_.replace("\u0000", ""))
              }
            }.toOption
          }
        case Nil =>
          Panic.!!!()
      }
    }
  }

}
