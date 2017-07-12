package lol.http

import cats.effect.IO
import fs2.{ Stream, Chunk }
import fs2.text

/** Support for Server Sent Events content. It allows a server to stream
  * events to a client. Events are string (utf-8) encoded, and a proper
  * `EventEncoder` or `EventDecoded` must be available for your event payload type.
  */
object ServerSentEvents {

  /** Encode the event payload as a string (utf-8). */
  trait EventEncoder[-A] {
    def apply(value: A): String
  }

  /** Provides default EventEncoders. */
  object EventEncoder {
    implicit val stringEncoder = new EventEncoder[String] {
      def apply(value: String) = value
    }
  }

  /** Decode the string event payload as a value of type A. */
  trait EventDecoder[+A] {
    def apply(data: String): IO[A]
  }

  /** Provides default EventDecoders. */
  object EventDecoder {
    implicit val stringDecoder = new EventDecoder[String] {
      def apply(value: String) = IO.pure(value)
    }
  }

  /** An event.
    * @param data the event payload.
    * @param event the event type (optional).
    * @param id the event id (optional).
    */
  case class Event[A](data: A, event: Option[String] = None, id: Option[String] = None)

  private def chunk(str: String) = Chunk.bytes(str.getBytes("utf8"))
  private val `\n` = chunk("\n")
  private val DATA = chunk("data: ")
  private val EVENT = chunk("event: ")
  private val ID = chunk("id: ")

  private[http] def encoder[A](eventEncoder: EventEncoder[A]): ContentEncoder[Stream[IO, Event[A]]] = new ContentEncoder[Stream[IO, Event[A]]] {
    def apply(events: Stream[IO, Event[A]]) = {
      val e = events.map {
        case Event(data, maybeEvent, maybeId) =>
          maybeEvent.map(str => Seq(EVENT, chunk(str), `\n`)).getOrElse(Nil) ++
          maybeId.map(str => Seq(ID, chunk(str), `\n`)).getOrElse(Nil) ++
          eventEncoder(data).split("\n").flatMap(str => Seq(DATA, chunk(str), `\n`)) ++
          Seq(`\n`)
      }.flatMap(s => Stream.emits(s)).flatMap(s => Stream.chunk(s))
      Content(e, Map(h"Content-Type" -> h"text/event-stream"))
    }
  }

  private[http] def decoder[A](eventDecoder: EventDecoder[A]): ContentDecoder[Stream[IO, Event[A]]] = new ContentDecoder[Stream[IO, Event[A]]] {
    def apply(content: Content) =
      if(content.headers.get(h"Content-Type").exists(_ == h"text/event-stream"))
        IO.pure {
          content.stream.through(text.utf8Decode).through(text.lines).
            scan(Left((new StringBuilder, None, None)):Either[(StringBuilder,Option[String],Option[String]), IO[Event[A]]]) {
              case (seed, line) =>
                val (dataBuffer, maybeEvent, maybeId) = seed match {
                  case Left(x) => x
                  case _ => (new StringBuilder, None, None)
                }
                if(line.startsWith("data:")) {
                  dataBuffer.append("\n").append(line.drop(5).trim)
                  Left((dataBuffer, maybeEvent, maybeId))
                }
                else if(line.startsWith("event:")) {
                  Left((dataBuffer, Some(line.drop(6).trim), maybeId))
                }
                else if(line.startsWith("id:")) {
                  Left((dataBuffer, Some(line.drop(3).trim), maybeId))
                }
                else if(line.isEmpty && !dataBuffer.isEmpty)  {
                  Right(eventDecoder(dataBuffer.toString.trim).map { data =>
                    Event(data, maybeEvent, maybeId)
                  })
                }
                // For now we just ignore invalid frames
                else {
                  Left((new StringBuilder, None, None))
                }
            }.
            collect { case Right(event) => event }.
            evalMap(identity)
        }
      else
        IO.raiseError {
          Error.UnexpectedContentType(s"Expected `text/event-stream' content but got `${content.headers.get(h"Content-Type").getOrElse("")}'")
        }
  }
}
