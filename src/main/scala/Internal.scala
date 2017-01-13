package lol.http

import fs2.{ Strategy, Chunk, Task, Stream, Pull, Sink, async }

import org.xnio.{ ChannelListener }
import org.xnio.channels.{ StreamSourceChannel, StreamSinkChannel }

import io.undertow.connector.{ ByteBufferPool }

import java.nio.{ ByteBuffer }

// Basically everything we don't want to expose in our public API
private object Internal {

  def extract(url: String): (String, String, Int, String, Option[String]) = {
    val url0 = new java.net.URL(url)
    val path = if(url0.getPath.isEmpty) "/" else url0.getPath
    val port = url0.getPort
    val host = url0.getHost
    val scheme = url0.getProtocol
    val queryString = Option(url0.getQuery)
    (scheme, host, if(port < 0) url0.getDefaultPort else port, path, queryString)
  }

  def source(
    in: StreamSourceChannel, byteBufferPool: ByteBufferPool
  )(implicit s: Strategy): Task[Stream[Task,Byte]] = for {
    // A semaphore used to synchronize over IO events
    SDataAvailable <- async.semaphore[Task](0)
    _ <- Task.delay {
      in.getReadSetter.set(new ChannelListener[StreamSourceChannel]() {
        override def handleEvent(channel: StreamSourceChannel): Unit = {
          Task.now(in.suspendReads())
            .flatMap(_ => SDataAvailable.increment)
            .unsafeRun
        }
      })
    }
  } yield {
    // A Stream lazily reading for the underlying channel
    def nextChunk(): Stream[Task,Byte] = {
      val readBuffer = byteBufferPool.allocate()
      try {
        val buffer = readBuffer.getBuffer()
        buffer.clear()

        in.read(buffer) match {
          case -1 =>
            // Content is finished, end the Stream
            Stream.empty
          case 0 =>
            // No more data available for now
            // Let's resume the reads, and wait for some data available before trying again
            Stream.eval(Task.delay(in.resumeReads).flatMap(_ => SDataAvailable.decrement)).flatMap { _ =>
              nextChunk()
            }
          case n =>
            // Read available data and produce a Chunk
            buffer.flip()
            val data = Array.ofDim[Byte](buffer.remaining)
            buffer.get(data)
            Stream.chunk(Chunk.bytes(data)) ++ nextChunk()
        }
      }
      catch { case e: Throwable =>
        Stream.fail(new Exception("IO read error"))
      }
      finally {
        readBuffer.close()
      }
    }
    Stream.eval(Task.delay(nextChunk())).flatMap(identity)
  }

  def sink(
    out: StreamSinkChannel
  )(implicit s: Strategy): Task[Sink[Task,Byte]] = for {
    // A semaphore used to synchronize over IO events
    SCanWrite <- async.semaphore[Task](0)
    _ <- Task.delay {
      out.getWriteSetter.set(new ChannelListener[StreamSinkChannel]() {
        override def handleEvent(channel: StreamSinkChannel): Unit = {
          Task.now(out.suspendWrites())
            .flatMap(_ => SCanWrite.increment)
            .unsafeRun
        }
      })
    }
  } yield {
    // A sink to the underlying channel
    _.repeatPull(_.awaitOption.flatMap {
      case Some((chunk, next)) =>
        val buffer = ByteBuffer.wrap(chunk.toArray)
        // Repeat until the buffer is completely written
        Pull.attemptEval(Stream.repeatEval(
          Task.delay(out.resumeWrites)
            .flatMap(_ => SCanWrite.decrement)
            .flatMap(_ => Task.delay(out.write(buffer)))
        ).takeWhile(_ => buffer.hasRemaining).run).flatMap {
          case Left(ioError) => Pull.fail(new Exception("IO write error"))
          case Right(x) => Pull.output1(x)
        } as next
      case None =>
        Pull.done
    })
  }

}
