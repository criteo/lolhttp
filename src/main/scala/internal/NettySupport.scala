package lol.http.internal

import fs2.{ Strategy, Chunk, Task, Pull, Sink }

import io.netty.channel.{ Channel, ChannelFuture }
import io.netty.util.concurrent.{ GenericFutureListener }
import io.netty.buffer.{ Unpooled, ByteBuf }
import io.netty.handler.codec.http.{
  DefaultHttpContent,
  DefaultLastHttpContent }

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
    def runInEventLoop[A](block: => A): Unit = channel.eventLoop.submit(
      new Runnable() { def run = block }
    )

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
