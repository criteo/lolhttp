package lol.http.internal

import fs2.{ Stream, Strategy, Chunk, Task, Pull, Sink }

import io.netty.channel.{ Channel, ChannelFuture }
import io.netty.util.concurrent.{ GenericFutureListener }
import io.netty.buffer.{ Unpooled, ByteBuf }
import io.netty.handler.codec.http.{
  HttpMessage,
  HttpHeaderNames,
  DefaultHttpContent,
  DefaultLastHttpContent }

import scala.util.{ Try, Failure }
import scala.concurrent.{
  Future,
  Promise,
  ExecutionContext }
import scala.collection.mutable.{ ListBuffer }

private[http] object NettySupport {

  implicit class NettyChannelFuture(f: ChannelFuture) {
    def toFuture: Future[Channel] = {
      val p = Promise[Channel]
      f.addListener(new GenericFutureListener[ChannelFuture] {
        override def operationComplete(f: ChannelFuture) = {
          if(f.isSuccess) {
            p.success(f.channel)
          }
          else {
            p.failure(f.cause)
          }
        }
      })
      p.future
    }
  }

  implicit class NettyByteBuffer(buffer: ByteBuf) {
    def toChunk: Chunk[Byte] = {
      val chunks = ListBuffer.empty[Chunk[Byte]]
      while(buffer.readableBytes > 0) {
        val bytes = Array.ofDim[Byte](buffer.readableBytes)
        buffer.readBytes(bytes)
        chunks += Chunk.bytes(bytes)
      }
      Chunk.concat(chunks)
    }
  }

  implicit class ChunkByteBuffer(chunk: Chunk[Byte]) {
    def toByteBuf: ByteBuf = Unpooled.wrappedBuffer(chunk.toArray)
  }

  implicit class BetterChannel(channel: Channel) {
    def runInEventLoop[A](block: => A): Future[Unit] = {
      val p = Promise[Unit]
      channel.eventLoop.submit(
        new Runnable() { def run = try { block; p.success(()) } catch { case e: Throwable => p.failure(e)} }
      )
      p.future
    }

    def writeMessage(message: HttpMessage, contentStream: Stream[Task,Byte])(implicit e: ExecutionContext): Unit = {
      val expectedLength: Option[Int] = Try(message.headers.get(HttpHeaderNames.CONTENT_LENGTH).toInt).toOption
      def go(data: Option[(Chunk[Byte], Stream[Task,Byte])]) = data match {
        case None =>
          channel.write(message)
          channel.writeAndFlush(new DefaultLastHttpContent())
          if(expectedLength.isEmpty) channel.close()
        case Some((head, tail)) =>
          channel.write(message)
          if(expectedLength.exists(_ == head.size)) {
            channel.writeAndFlush(head.toByteBuf)
            channel.writeAndFlush(new DefaultLastHttpContent())
          }
          else {
            (for {
              _ <- channel.writeAndFlush(head.toByteBuf).toFuture
              _ <- (tail to channel.httpContentSink).run.unsafeRunAsyncFuture()
            } yield {
              if(expectedLength.isEmpty) channel.close()
            }).
            andThen { case Failure(_) => channel.close() }
          }
      }
      contentStream.uncons.runLast.map(_.flatten).unsafeAttemptRunSync() match {
        case Right(Right(data)) =>
          go(data)
        case Right(Left(e)) =>
          channel.close()
        case Left(continuation) =>
          continuation {
            case Right(data) =>
              go(data)
            case Left(e) =>
              channel.close()
          }
      }
    }

    def httpContentSink(implicit e: ExecutionContext): Sink[Task,Byte] = {
      implicit val S = Strategy.fromExecutionContext(e)
      _.repeatPull(_.awaitOption.flatMap {
        case Some((chunk, h)) =>
          Pull.eval(Task.fromFuture {
            channel.writeAndFlush(new DefaultHttpContent(chunk.toByteBuf)).toFuture
          }) as h
        case None =>
          Pull.eval(Task.fromFuture {
            channel.writeAndFlush(new DefaultLastHttpContent()).toFuture
          }) >> Pull.done
      })
    }

    def bytesSink(implicit e: ExecutionContext): Sink[Task,Byte] = {
      implicit val S = Strategy.fromExecutionContext(e)
      _.repeatPull(_.awaitOption.flatMap {
        case Some((chunk, h)) =>
          Pull.eval(Task.fromFuture {
            channel.writeAndFlush(chunk.toByteBuf).toFuture
          }) as h
        case None =>
          Pull.done
      })
    }
  }

}
