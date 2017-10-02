package lol.http.internal

import scala.concurrent.ExecutionContext

import cats.effect.{ IO }
import fs2.{ Chunk, Pull, Segment, Sink, Stream, async }

import io.netty.channel.{
  Channel,
  ChannelFuture,
  ChannelHandlerContext,
  SimpleChannelInboundHandler }
import io.netty.handler.logging.{ LogLevel, LoggingHandler }
import io.netty.util.concurrent.{ GenericFutureListener }
import io.netty.buffer.{ Unpooled, ByteBuf }
import io.netty.handler.codec.http.{
  DefaultHttpContent,
  DefaultHttpRequest,
  DefaultHttpResponse,
  DefaultLastHttpContent,
  HttpClientCodec,
  HttpContent,
  HttpContentDecompressor,
  HttpMessage,
  HttpObject,
  HttpRequest,
  HttpRequestDecoder,
  HttpResponse,
  HttpResponseEncoder,
  HttpResponseStatus,
  HttpUtil,
  LastHttpContent,
  HttpMethod => NettyHttpMethod,
  HttpVersion => NettyHttpVersion }
import io.netty.handler.codec.http2.{
  Http2Headers,
  Http2ConnectionHandler,
  Http2FrameListener,
  Http2ConnectionDecoder,
  Http2ConnectionEncoder,
  Http2Settings,
  AbstractHttp2ConnectionHandlerBuilder,
  Http2FrameLogger,
  Http2Flags,
  DefaultHttp2Headers
}

import java.util.concurrent.atomic.{ AtomicInteger }

import scala.concurrent.{ Future, Promise }
import scala.collection.mutable.{ ListBuffer }
import collection.JavaConverters._

import lol.http._

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
    def toIO: IO[Channel] = {
      IO.async { cb =>
        try {
          f.addListener(new GenericFutureListener[ChannelFuture] {
            override def operationComplete(f: ChannelFuture) = {
              if(f.isSuccess) {
                cb(Right(f.channel))
              }
              else {
                cb(Left(f.cause))
              }
            }
          })
        }
        catch {
          case e: Throwable =>
            cb(Left(e))
        }
      }
    }
  }

  implicit class NettyByteBuffer(buffer: ByteBuf) {
    def toSegment: Segment[Byte, Unit] = {
      val segments = ListBuffer.empty[Segment[Byte, Unit]]
      while (buffer.readableBytes > 0) {
        val bytes = Array.ofDim[Byte](buffer.readableBytes)
        buffer.readBytes(bytes)
        segments += Chunk.bytes(bytes)
      }
      segments.foldLeft(Segment.empty[Byte])((acc, seg) => acc ++ seg)
    }

    def toChunk: Chunk[Byte] = {
      val chunks = ListBuffer.empty[Chunk[Byte]]
      while (buffer.readableBytes > 0) {
        val bytes = Array.ofDim[Byte](buffer.readableBytes)
        buffer.readBytes(bytes)
        chunks += Chunk.bytes(bytes)
      }
      Segment.seq(chunks).flattenChunks.toChunk
    }
  }

  implicit class NettyChannel(channel: Channel) {
    def runInEventLoop[A](thunk: () => A): A = {
      val latch = new java.util.concurrent.CountDownLatch(1)
      @volatile var result: Option[A] = None
      channel.eventLoop.submit(new Runnable() {
        def run = {
          result = Some(thunk())
          latch.countDown
        }
      })
      latch.await
      result.get
    }

    def runInEventLoopAsync[A](thunk: () => A): IO[A] = {
      IO.async(k => channel.eventLoop.submit(new Runnable() {
        def run = try {
          k(Right(thunk()))
        } catch {
          case e: Throwable => k(Left(e))
        }
      }))
    }
  }

  implicit class SegmentByteBuffer(segment: Segment[Byte, Unit]) {
    def toByteBuf: ByteBuf = Unpooled.wrappedBuffer(segment.toChunk.toArray)
  }

  implicit class ChunkByteBuffer(chunk: Chunk[Byte]) {
    def toByteBuf: ByteBuf = Unpooled.wrappedBuffer(chunk.toArray)
  }

  object Netty {
    def clientConnection(channel: Channel, debug: Option[String], protocol: String)(implicit ec: ExecutionContext): ClientConnection = 
      protocol match {
        case `HTTP2` =>
          val http2xConnection = new Http2xConnection(channel, client = true, debug)
          val waitingResponses = collection.concurrent.TrieMap.empty[Int,(Either[Throwable, Response]) => Unit]
          http2xConnection.incomingMessages.evalMap {
            case (stream, headers, contentStream) => IO {
              waitingResponses.get(stream) match {
                case Some(cb) =>
                  cb(
                    (for {
                      // Track the number of content readers
                      readers <- async.semaphore[IO](1)
                    } yield Right(Response(
                      status = headers.status.toString.toInt,
                      headers = headers.iterator.asScala.map { h =>
                        (HttpString(h.getKey.toString), HttpString(h.getValue.toString))
                      }.toMap.filterKeys(!_.toString.startsWith(":")),
                      content = Content(
                        stream =
                          Stream.
                            // The content stream can be read only once
                            eval(readers.tryDecrement).flatMap {
                              case false =>
                                Stream.fail(Error.StreamAlreadyConsumed)
                              case true =>
                                contentStream
                            },
                        headers = headers.iterator.asScala.map { h =>
                          (HttpString(h.getKey.toString), HttpString(h.getValue.toString))
                        }.toMap.filterKeys(_.toString.toLowerCase.startsWith("content-"))
                      )
                    ))).unsafeRunSync()
                  )
                case None =>
                  channel.close()
              }
            }
          }.run.unsafeRunAsync(_ => if(channel.isOpen) channel.close())
          new ClientConnection {
            def apply(request: Request, release: () => Unit): IO[Response] = {
              release()
              val requestHeaders = {
                new DefaultHttp2Headers().
                  method(request.method.toString).
                  path(request.path.toString).
                  scheme(request.scheme.toString)
              }
              (request.content.headers ++ request.headers).foreach { case (key, value) =>
                requestHeaders.add(key.toString.toLowerCase, value.toString)
              }
              for {
                _ <- IO.fromFuture(cats.Now(http2xConnection.isReady))
                stream <- http2xConnection.write(-1, requestHeaders, request.content.stream)
                response <- IO.async[Response](cb => waitingResponses += (stream -> cb))
              } yield response
            }
            def isOpen = http2xConnection.isOpen
            def close = http2xConnection.close
            def closed = http2xConnection.closed
          }
        case `HTTP` =>
          debug.foreach(logger => channel.pipeline.addLast("Debug", new LoggingHandler(logger, LogLevel.INFO)))
          channel.pipeline.addLast("HttpClientCodec", new HttpClientCodec())
          channel.pipeline.addLast("HttpDecompress", new HttpContentDecompressor())
          val http1xConnection = new Http1xConnection(channel, client = true)
          new ClientConnection {
            def apply(request: Request, release: () => Unit): IO[Response] =
              for {
                nettyRequest <- {
                  val nettyRequest = new DefaultHttpRequest(
                    NettyHttpVersion.HTTP_1_1,
                    new NettyHttpMethod(request.method.toString),
                    s"${request.path}${request.queryString.map(q => s"?$q").getOrElse("")}"
                  )
                  (request.content.headers ++ request.headers).foreach { case (key,value) =>
                    nettyRequest.headers.set(key.toString, value.toString)
                  }
                  http1xConnection.write(nettyRequest, request.content.stream).map { _ =>
                    nettyRequest
                  }
                }
                response <- http1xConnection.read.flatMap {
                  case (nettyResponse: HttpResponse, contentStream) =>
                    for {
                      readers <- async.semaphore[IO](1)
                      upgradedReaders <- async.semaphore[IO](1)
                    } yield {
                      val response: Response = Response(
                        status = nettyResponse.status.code,
                        headers = nettyResponse.headers.asScala.map { h =>
                          (HttpString(h.getKey), HttpString(h.getValue))
                        }.toMap,
                        content =
                          Content(
                            stream =
                              Stream.
                                // The content stream can be read only once
                                eval(readers.tryDecrement).flatMap {
                                  case false =>
                                    Stream.fail(Error.StreamAlreadyConsumed)
                                  case true =>
                                    contentStream.onFinalize(
                                      if(HttpUtil.isKeepAlive(nettyRequest) && HttpUtil.isKeepAlive(nettyResponse)) {
                                        IO(release())
                                      }
                                      else {
                                        http1xConnection.close
                                      }
                                    )
                                },
                              headers = nettyResponse.headers.asScala.map { h =>
                                (HttpString(h.getKey), HttpString(h.getValue))
                              }.toMap.filter(_._1.toString.toLowerCase.startsWith("content-"))
                          ),
                        upgradeConnection = nettyResponse.status.code match {
                          case 101 => (upstream) =>
                            Stream.eval(readers.tryDecrement).
                              flatMap {
                                case false => Stream.emit(())
                                // If user code did not read the response content yet,
                                // we need to drain the content stream before upgrading
                                // the connection.
                                case true => Stream.eval(contentStream.drain.run)
                              }.
                              flatMap { _ =>
                                Stream.eval(upgradedReaders.tryDecrement).
                                  flatMap {
                                    case false =>
                                      Stream.fail(Error.StreamAlreadyConsumed)
                                    case true =>
                                      Stream.eval(http1xConnection.upgrade()).flatMap { downstream =>
                                        downstream.
                                          merge((upstream to http1xConnection.writeBytes).drain).
                                          onFinalize(http1xConnection.close)
                                      }
                                  }
                              }
                          case _ => _ => Stream.fail(Error.UpgradeRefused)
                        }
                      )
                      response
                    }
                  case x =>
                    Panic.!!!(s"Expected HttpResponse, got ${x}")
                }
              } yield response

            def isOpen = http1xConnection.isOpen
            def close = http1xConnection.close
            def closed = http1xConnection.closed
          }
      }

    def serverConnection(channel: Channel, debug: Option[String], protocol: String)(implicit ec: ExecutionContext): ServerConnection =
      protocol match {
        case `HTTP2` =>
          val http2xConnection = new Http2xConnection(channel, client = false, debug)
          new ServerConnection {
            def apply(): IO[(Request, Response => IO[Unit])] =
              for {
                message <- http2xConnection.read().flatMap {
                  case (stream, headers, contentStream) =>
                    for {
                      // Track the number of content readers
                      readers <- async.semaphore[IO](1)
                    } yield {
                      stream -> Request(
                        method = HttpMethod(headers.method.toString),
                        url = headers.path.toString,
                        headers = headers.iterator.asScala.map { h =>
                          (HttpString(h.getKey.toString), HttpString(h.getValue.toString))
                        }.toMap.filterKeys(!_.toString.startsWith(":")),
                        content = Content(
                          stream =
                            Stream.
                              // The content stream can be read only once
                              eval(readers.tryDecrement).flatMap {
                                case false =>
                                  Stream.fail(Error.StreamAlreadyConsumed)
                                case true =>
                                  contentStream
                              },
                          headers = headers.iterator.asScala.map { h =>
                            (HttpString(h.getKey.toString), HttpString(h.getValue.toString))
                          }.toMap.filterKeys(_.toString.toLowerCase.startsWith("content-"))
                        ),
                        protocol = HTTP2
                      )
                    }
                }
                (stream, request) = message
              } yield {
                request -> /* Callback to send the response */ (response => {
                  val responseHeaders = new DefaultHttp2Headers().status(HttpResponseStatus.valueOf(response.status).codeAsText)
                  (response.content.headers ++ response.headers).foreach { case (key, value) =>
                    responseHeaders.add(key.toString.toLowerCase, value.toString)
                  }
                  if(request.method == HEAD) {
                    responseHeaders.add("content-length", "0")
                    http2xConnection.write(stream, responseHeaders, Stream.empty).map(_ => ())
                  }
                  else {
                    http2xConnection.write(stream, responseHeaders, response.content.stream).map(_ => ())
                  }
                })
              }
            def isOpen = http2xConnection.isOpen
            def close = http2xConnection.close
            def closed = http2xConnection.closed
          }
        case `HTTP` =>
          debug.foreach(logger => channel.pipeline.addLast("Debug", new LoggingHandler(logger, LogLevel.INFO)))
          channel.pipeline.addLast("HttpRequestDecoder", new HttpRequestDecoder())
          channel.pipeline.addLast("HttpResponseEncoder", new HttpResponseEncoder())
          val http1xConnection = new Http1xConnection(channel, client = false)
          new ServerConnection {
            // For HTTP/1.1 we won't accept new requests until the response
            // for the previous one has not been totally flushed. Thus this lock.
            val lock = async.semaphore[IO](1).unsafeRunSync()
            def apply(): IO[(Request, Response => IO[Unit])] =
              for {
                _ <- async.race(lock.decrement, http1xConnection.closed)
                request <- http1xConnection.read.flatMap {
                  case (nettyRequest: HttpRequest, contentStream) =>
                    for {
                      // Track the number of content readers
                      readers <- async.semaphore[IO](1)
                    } yield {
                      Request(
                        method = HttpMethod(nettyRequest.method.name),
                        url = nettyRequest.uri,
                        headers = nettyRequest.headers.asScala.map { h =>
                          (HttpString(h.getKey), HttpString(h.getValue))
                        }.toMap,
                        content = Content(
                          stream =
                            Stream.
                              // The content stream can be read only once
                              eval(readers.tryDecrement).flatMap {
                                case false =>
                                  Stream.fail(Error.StreamAlreadyConsumed)
                                case true =>
                                  contentStream
                              },
                          headers = nettyRequest.headers.asScala.map { h =>
                            (HttpString(h.getKey), HttpString(h.getValue))
                          }.toMap.filter(_._1.toString.toLowerCase.startsWith("content-"))
                        )
                      )
                    }
                  case x =>
                    Panic.!!!(s"Expected HttpRequest, got ${x}")
                }
              } yield {
                request -> /* Callback to send the response */ (response => {
                  val nettyResponse = new DefaultHttpResponse(
                    NettyHttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(response.status)
                  )
                  (response.content.headers ++ response.headers).foreach { case (key,value) =>
                    nettyResponse.headers.set(key.toString, value.toString)
                  }
                  // Write the HTTP response
                  for {
                    _ <- http1xConnection.write(nettyResponse, response.content.stream)
                    _ <- {
                      if(response.status == 101) {
                        http1xConnection.upgrade().flatMap { upstream =>
                          val downstream = response.upgradeConnection(upstream)
                          (downstream to http1xConnection.writeBytes).drain.run
                        }
                      }
                      else {
                        IO.unit
                      }
                    }
                    _ <- lock.increment
                  } yield ()
                })
              }

            def isOpen = http1xConnection.isOpen
            def close = http1xConnection.close
            def closed = http1xConnection.closed
          }
        case x =>
          Panic.!!!(s"Unsupported protocol, $x")
      }
  }

  trait ClientConnection {
    // Send a request and a callback allowing the underlying impl to
    // release the connection, and eventually receive the response.
    def apply(request: Request, release: () => Unit): IO[Response]
    def isOpen: Boolean
    def close: IO[Unit]
    def closed: IO[Unit]
  }
  trait ServerConnection {
    // Wait for a request and a callback allowing to send the correponding
    // response when it is ready.
    def apply(): IO[(Request, Response => IO[Unit])]
    def isOpen: Boolean
    def close: IO[Unit]
    def closed: IO[Unit]
  }

  class Http1xConnection(channel: Channel, client: Boolean)(implicit ec: ExecutionContext) {
    // At first we set the channel in auto read to get the first message
    channel.config.setAutoRead(true)

    val (messages, content, permits) = (for {
      // The HTTP messages buffer. We use an unboundedQueue here but
      // because we only allow 1 message at a time, the effective size will be 1.
      messages <- async.unboundedQueue[IO,Option[(HttpMessage,Boolean)]]
      // The content buffer. We use also an unboundedQueue here but
      // because we ask netty to stop to read as soon as we have one chunk,
      // so the effective size will be 1 as well.
      content <- async.unboundedQueue[IO,Option[Chunk[Byte]]]
      // Track usages. We only allow one message to be write/read at a time.
      permits <- async.semaphore[IO](1)
    } yield (messages, content, permits)).unsafeRunSync()

    // Each time we consume a chunk we ask the channel
    // to read the next message. When this chunk has been
    // enqueued no more data were available on the socket.
    // Because we are now consuming this chunk, we can inform the
    // socket that we are ready to receive new data.
    val contentStream =
      content.dequeue.evalMap { chunk =>
        if(chunk.isDefined) channel.runInEventLoopAsync(() => channel.read()).map(_ => chunk) else IO.pure(chunk)
      }

    channel.pipeline.addLast("HttpStreamHandler", new SimpleChannelInboundHandler[HttpObject]() {
      // Mutable reference is safe here because the code is single threaded.
      // For performance reason we don't push a chunk to the queue each time
      // a message has been read by netty. We buffer up to the maximum chunk size.
      var buffer: Chunk[Byte] = Chunk.empty

      // Optimization for message with no content.
      var skipContent: Boolean = false
      def hasContent(message: HttpMessage) = message match {
        case req: HttpRequest if req.method == NettyHttpMethod.GET || req.method == NettyHttpMethod.HEAD => false
        case req: HttpRequest => HttpUtil.getContentLength(req, 0) > 0
        case _ => true
      }

      override def channelRead0(ctx: ChannelHandlerContext, msg: HttpObject) = msg match {
        // New HTTP message, enqueue it in the message queue
        // disable autoread, so the message content will be pulled
        // and read a first chunk of content.
        case message: HttpMessage =>
          (if(hasContent(message)) {
            (for {
              _ <- messages.enqueue1(Some(message -> true))
              _ <- IO(skipContent = false)
              _ <- IO(channel.config.setAutoRead(false))
            } yield ())
          }
          else {
            (for {
              _ <- messages.enqueue1(Some(message -> false))
              _ <- IO(skipContent = true)
            } yield ())
          }).unsafeRunSync()
        // We ignore the content and the last chunk has been received,
        // mark the connection ready for next message.
        case lastChunk: LastHttpContent if skipContent =>
          (if(client) permits.increment else permits.decrement).unsafeRunSync()
        // Should not happen, unless the client send us a GET/HEAD request
        // with a content body and we will ignore it anyawy
        case chunk: HttpContent if skipContent =>
        // Last chunk has been received, so it is the end of the
        // message content. Enqueue the rest of the buffer if needed,
        // enqueue the last chunk and enqueue a None to signal that it
        // is the end of the content stream. Reset the buffer, and enable
        // autoread to wait for the next message.
        case lastChunk: LastHttpContent =>
          (for {
            _ <- content.enqueue1(Some(buffer))
            _ <- content.enqueue1(Some(lastChunk.content.toChunk))
            _ <- content.enqueue1(None)
            _ <- IO {
              buffer = Chunk.empty
              channel.config.setAutoRead(true)
            }
          } yield ()).unsafeRunSync()
        // A content chunk has been received. Add it to the buffer.
        case chunk: HttpContent =>
          buffer = chunk.content.toChunk.prepend(buffer).toChunk
      }

      // No more data available on the socket. Enqueue the buffered
      // data to the content queue and clear the buffer.
      override def channelReadComplete(ctx: ChannelHandlerContext) = {
        (for {
          _ <- content.enqueue1(Some(buffer))
          _ <- IO(buffer = Chunk.empty)
        } yield ()).unsafeRunSync()
      }
    })

    channel.pipeline.addLast("CatchAll", new SimpleChannelInboundHandler[Any] {
      override def channelRead0(ctx: ChannelHandlerContext, msg: Any) = msg match {
        // If we have switched protocol, we now receive raw bytes buffer here.
        case msg: ByteBuf =>
          content.enqueue1(Some(msg.toChunk)).unsafeRunSync()
          // Stop reading automatically now, user code will pull the stream.
          channel.config.setAutoRead(false)
        case _ =>
          Panic.!!!(s"Missed $msg")
      }
      override def exceptionCaught(ctx: ChannelHandlerContext, e: Throwable) = {
        ctx.close()
        e match {
          case Panic(_) => throw e
          case e =>
        }
      }
    })

    // When the channel is closed we push None to the message
    // queue, to indicate the End Of Stream. We also push None
    // to the content queue to force incomplete stream to finish.
    lazy val closed: IO[Unit] = channel.closeFuture.toIO.flatMap { _ =>
      for {
        _ <- messages.enqueue1(None)
        _ <- content.enqueue1(None)
      } yield ()
    }

    def isOpen: Boolean = channel.isOpen
    def close: IO[Unit] = IO(if(channel.isOpen) channel.close())

    // Read one HTTP message along with its content stream. The
    // content stream must be read before the next message to be
    // available.
    def read: IO[(HttpMessage, Stream[IO, Byte])] =
      messages.dequeue1.flatMap {
        case Some((message, true)) =>
          for {
            readers <- async.semaphore[IO](1)
            eosReached <- async.signalOf[IO, Boolean](false)
            messageStream =
              Stream.
                // The content stream can be read only once
                eval(readers.tryDecrement).flatMap {
                case false =>
                  Stream.fail(Error.StreamAlreadyConsumed)
                case true =>
                  contentStream.
                    // We read the queue until a None, that marks
                    // the content stream end.
                    evalMap(chunk => eosReached.set(chunk.isEmpty).map(_ => chunk)).
                    takeWhile(_.isDefined).
                    // The stream of bytes
                    flatMap(chunk => Stream.chunk(chunk.get)).
                    // When user code finishes consuming this stream, we need
                    // to drain the remaining content if the eos has not been
                    // reached yet.
                    onFinalize {
                      for {
                        fullyRead <- eosReached.get
                        _ <- if (fullyRead) IO.unit else contentStream.takeWhile(_.isDefined).drain.run
                        _ <- if (client) permits.increment else permits.decrement
                      } yield ()
                    }
              }
          } yield (message, messageStream)
        case Some((message, false)) => IO.pure((message, Stream.empty))
        case _ => IO.raiseError(Error.ConnectionClosed)
      }


    // Write an HTTP message along with its content to the channel.
    def write(message: HttpMessage, contentStream: Stream[IO,Byte]): IO[Unit] =
      for {
        _ <- if(client) permits.decrement else permits.increment
        _ <- if(channel.isOpen) IO(channel.writeAndFlush(message)) else IO.raiseError(Error.ConnectionClosed)
        _ <- (contentStream to httpContentSink).run
        _ <- IO(if(message.isInstanceOf[HttpResponse] && HttpUtil.getContentLength(message, -1) < 0) channel.close())
      } yield ()

    // Upgrade the connection to a plain TCP connection: we deregister
    // all the netty HTTP pipeline.
    def upgrade(): IO[Stream[IO,Byte]] =
      for {
        _ <- permits.decrement
        _ <- messages.enqueue1(None)
        in <- IO {
          channel.runInEventLoop[Stream[IO,Byte]] { () =>
            channel.pipeline.names.asScala.filter(_.startsWith("Http")).foreach(channel.pipeline.remove)
            // Read the next message
            channel.read()
            // The incoming stream
            contentStream.
              takeWhile(_.isDefined).
              flatMap(chunk => Stream.chunk(chunk.get))
          }
        }
      } yield (in)

    def writeBytes = bytesSink

    def httpContentSink: Sink[IO, Byte] = {
      _.repeatPull { s =>
        s.unconsChunk.flatMap {
          case Some((chunk, t)) =>
            Pull.eval {
              if (channel.isOpen) channel.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(chunk.toArray))).toIO
              else IO.raiseError(Error.ConnectionClosed)
            }.as(Some(t))
          case None =>
            Pull.eval {
              if (channel.isOpen) channel.writeAndFlush(new DefaultLastHttpContent()).toIO
              else IO.raiseError(Error.ConnectionClosed)
            } >> Pull.pure(None)
        }
      }
    }

    def bytesSink: Sink[IO, Byte] = {
      _.repeatPull { s =>
        s.unconsChunk.flatMap {
          case Some((chunk, t)) =>
            Pull.eval {
              if (channel.isOpen) channel.writeAndFlush(Unpooled.wrappedBuffer(chunk.toArray)).toIO
              else IO.raiseError(Error.ConnectionClosed)
            }.as(Some(t))
          case None =>
            Pull.pure(None)
        }
      }
    }
  }

  class Http2xConnection(channel: Channel, client: Boolean, logger: Option[String])(implicit ec: ExecutionContext) {
    private val messages = async.unboundedQueue[IO,Option[(Int,Http2Headers,Stream[IO,Byte])]].unsafeRunSync()
    private val contentStreams = collection.concurrent.TrieMap.empty[Int,async.mutable.Queue[IO,Option[Chunk[Byte]]]]
    private var ctx: ChannelHandlerContext = _
    private val mounted = Promise[Unit]

    private class Http2Handler(decoder: Http2ConnectionDecoder, encoder: Http2ConnectionEncoder, initialSettings: Http2Settings) extends Http2ConnectionHandler(decoder, encoder, initialSettings) with Http2FrameListener {
      override def handlerAdded(ctx0: ChannelHandlerContext): Unit = {
        super.handlerAdded(ctx0)
        ctx = ctx0
        mounted.success(())
      }

      def onHeadersRead(ctx: ChannelHandlerContext, stream: Int, headers: Http2Headers, padding: Int, eos: Boolean): Unit = {
        val contentStream: Stream[IO,Byte] = if(eos) Stream.empty else {
          (for {
            eosReached <- async.signalOf[IO, Boolean](false)
            queue <- async.unboundedQueue[IO,Option[Chunk[Byte]]]
            contentStream = {
              queue.dequeue.
                evalMap(chunk => eosReached.set(chunk.isEmpty).map(_ => chunk)).
                takeWhile(_.isDefined).
                evalMap(chunk =>
                  channel.runInEventLoopAsync { () =>
                    if(handler.decoder.flowController.consumeBytes(handler.connection.stream(stream), chunk.get.size)) {
                      handler.flush(ctx)
                    }
                    chunk
                  })
            }
            _ <- IO { contentStreams.put(stream, queue) }
          } yield {
            contentStream.
              flatMap(chunk => Stream.chunk(chunk.get)).
              // When user code finishes consuming this stream, we need
              // to drain the remaining content if the eos has not been
              // reached yet.
              onFinalize {
                for {
                  fullyRead <- eosReached.get
                  _ <- if (fullyRead) IO.unit else contentStream.takeWhile(_.isDefined).drain.run
                } yield ()
              }
          }).unsafeRunSync()
        }
        messages.enqueue1(Some((stream, headers, contentStream))).unsafeRunAsync(_ => ())
      }

      def onDataRead(ctx: ChannelHandlerContext, stream: Int, data: ByteBuf, padding: Int, eos: Boolean): Int = {
        contentStreams.get(stream).map { q =>
          q.enqueue1(Some(data.toChunk)).unsafeRunSync()
          if(eos) {
            q.enqueue1(None).unsafeRunAsync { _ =>
              contentStreams.remove(stream)
            }
          }
          padding
        }.getOrElse {
          channel.close()
          0
        }
      }

      def onRstStreamRead(ctx: ChannelHandlerContext, stream: Int, errorCode: Long): Unit = contentStreams.remove(stream)
      def onGoAwayRead(ctx: ChannelHandlerContext, lastStream: Int, errorCode: Long, debugData: ByteBuf): Unit = channel.close()
      def onUnknownFrame(ctx: ChannelHandlerContext, frameType: Byte, stream: Int, flags: Http2Flags, data: ByteBuf): Unit = channel.close()

      def onHeadersRead(ctx: ChannelHandlerContext, stream: Int, headers: Http2Headers, streamDependency: Int, weight: Short, exclusive: Boolean, padding: Int, eos: Boolean): Unit = onHeadersRead(ctx, stream, headers, padding, eos)
      def onPingAckRead(ctx: ChannelHandlerContext, data: ByteBuf): Unit = ()
      def onPingRead(ctx: ChannelHandlerContext, data: ByteBuf): Unit = ()
      def onPriorityRead(ctx: ChannelHandlerContext, stream: Int, streamDependency: Int, weight: Short, exclusive: Boolean): Unit = ()
      def onPushPromiseRead(ctx: ChannelHandlerContext, stream: Int, promiseStream: Int, headers: Http2Headers, padding: Int): Unit = ()
      def onSettingsAckRead(ctx: ChannelHandlerContext): Unit = ()
      def onSettingsRead(ctx: ChannelHandlerContext, settings: Http2Settings): Unit = ()
      def onWindowUpdateRead(ctx: ChannelHandlerContext, stream: Int, windowSizeIncrement: Int): Unit = ()
    }
    private class Http2HandlerBuilder extends AbstractHttp2ConnectionHandlerBuilder[Http2Handler, Http2HandlerBuilder] {
      override def isServer = !client
      override def build() = {
        logger.foreach(logger => frameLogger(new Http2FrameLogger(LogLevel.INFO, logger)))
        super.build()
      }
      override def build(decoder: Http2ConnectionDecoder, encoder: Http2ConnectionEncoder, initialSettings: Http2Settings) = {
        val handler = new Http2Handler(decoder, encoder, initialSettings)
        frameListener(handler)
        handler
      }
    }
    private val handler = new Http2HandlerBuilder().build()
    channel.pipeline.addLast("Http2", handler)

    lazy val isReady = mounted.future
    lazy val incomingMessages: Stream[IO,(Int,Http2Headers,Stream[IO,Byte])] =
      messages.dequeue.takeWhile(_.isDefined).map(_.get)

    def read(): IO[(Int,Http2Headers,Stream[IO,Byte])] =
      messages.dequeue1.flatMap {
        case Some(next) =>
          IO.pure(next)
        case _ =>
          IO.raiseError(Error.ConnectionClosed)
      }

    def dataSink(stream: Int): Sink[IO,Byte] = {
      _.repeatPull(_.unconsChunk.flatMap {
        case Some((chunk, h)) =>
          Pull.eval(
            if(channel.isOpen) {
              channel.runInEventLoopAsync { () =>
                val f = handler.encoder.writeData(ctx, stream, chunk.toByteBuf, 0, false, ctx.newPromise)
                handler.flush(ctx)
                f.toIO
              }.flatMap(identity)
            }
            else
              IO.raiseError(Error.ConnectionClosed)
          ) as Some(h)
        case None =>
          Pull.eval(
            if(channel.isOpen) {
              channel.runInEventLoopAsync { () =>
                val f = handler.encoder.writeData(ctx, stream, Chunk.empty[Byte].toByteBuf, 0, true, ctx.newPromise)
                handler.flush(ctx)
                f.toIO
              }.flatMap(identity)
            }
            else
              IO.raiseError(Error.ConnectionClosed)
          ) >> Pull.pure(None)
      })
    }

    val streamCounter = new AtomicInteger(1)
    def write(stream: Int, message: Http2Headers, contentStream: Stream[IO,Byte]): IO[Int] = {
      for {
        streamId <- if(channel.isOpen) {
          channel.runInEventLoopAsync { () =>
            val streamId = if(stream == -1) streamCounter.addAndGet(2) else stream
            val f = handler.encoder.writeHeaders(ctx, streamId, message, 0, false, ctx.newPromise)
            handler.flush(ctx)
            f.toIO.map(_ => streamId)
          }.flatMap(identity)
        } else IO.raiseError(Error.ConnectionClosed)
        _ <- (contentStream to dataSink(streamId)).run
      } yield streamId
    }

    lazy val closed: IO[Unit] = channel.closeFuture.toIO.flatMap { _ =>
      messages.enqueue1(None)
    }

    def isOpen: Boolean = channel.isOpen
    def close: IO[Unit] = IO(if(channel.isOpen) channel.close())
  }

}
