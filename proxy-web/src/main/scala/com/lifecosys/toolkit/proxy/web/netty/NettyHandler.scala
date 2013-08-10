package com.lifecosys.toolkit.proxy.web.netty

import com.lifecosys.toolkit.proxy._
import org.jboss.netty.channel._
import org.jboss.netty.buffer.{ ChannelBuffers, ChannelBuffer }
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import com.typesafe.scalalogging.slf4j.Logging
import java.nio.ByteBuffer
import org.jboss.netty.handler.codec.http._
import java.nio.channels.ClosedChannelException
import com.lifecosys.toolkit.proxy.web._
import com.lifecosys.toolkit.proxy.DataHolder
import com.lifecosys.toolkit.proxy.ChannelKey
import scala.Some
import java.util.concurrent.{ Executor, Executors }
import org.jboss.netty.channel.socket.nio.{ NioClientSocketChannelFactory, NioWorker, NioWorkerPool }
import javax.servlet.AsyncContext
import java.util.concurrent.atomic.AtomicBoolean
import com.lifecosys.toolkit.proxy.DataHolder
import com.lifecosys.toolkit.proxy.ChannelKey
import com.lifecosys.toolkit.proxy.web.Message
import scala.Some
import scala.util.{ Success, Failure, Try }

/**
 *
 *
 * @author Young Gu
 * @version 1.0 8/9/13 1:40 PM
 */
trait ChannelManager extends Logging {
  private[this] val channels = scala.collection.mutable.Map[ChannelKey, Channel]()
  val requests = new scala.collection.mutable.SynchronizedQueue[ChannelBuffer]()

  def get(channelKey: ChannelKey) = channels.get(channelKey)
  def getChannels = Map[ChannelKey, Channel]() ++ channels

  def add(channelKey: ChannelKey, channel: Channel) = synchronized(channels += channelKey -> channel)
  def remove(channelKey: ChannelKey) = {
    synchronized(channels -= channelKey)
    logger.error(s"ChannelManager has ${getChannels.size} pending request:\n${getChannels.map(_._1.sessionId).mkString("\n")}")
  }
}

abstract class Task(channelKey: ChannelKey, handler: HttpsOutboundHandler) extends Logging {
  val channel = {
    logger.debug(s"Connecting to: ${channelKey.proxyHost}")
    val clientBootstrap = newClientBootstrap
    clientBootstrap.setFactory(clientSocketChannelFactory)
    clientBootstrap.getPipeline.addLast("handler", handler)
    val channelFuture = clientBootstrap.connect(channelKey.proxyHost.socketAddress).awaitUninterruptibly()
    val channel: Channel = channelFuture.getChannel
    //TODO:Update buffer size.
    channel.getConfig.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(DEFAULT_BUFFER_SIZE))
    logger.debug(s"Connect to completed: ${channelKey.proxyHost}, status: ${channel.isConnected}")
    channel
  }
  def submit(message: Message): Unit
}

abstract class BaseHttpsRequestProcessor extends web.RequestProcessor {
  val tasks = scala.collection.mutable.Map[ChannelKey, Task]()
  def releaseTask(channelKey: ChannelKey)

}

class MultiTaskHttpsRequestProcessor extends BaseHttpsRequestProcessor {

  def releaseTask(channelKey: ChannelKey) {
    tasks -= channelKey
  }

  def process(channelKey: ChannelKey, proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse) {
    class QueuedTask(channelKey: ChannelKey, handler: HttpsOutboundHandler) extends Task(channelKey, handler) {
      val isRunning = new AtomicBoolean()
      val messages = new scala.collection.mutable.SynchronizedQueue[Message]()

      def submit(message: Message) = channelConnected {
        message.response.setStatus(HttpServletResponse.SC_OK)
        message.response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
        message.response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
        // Initiate chunked encoding by flushing the headers.
        message.response.getOutputStream.flush()
        process(message)
      }

      def process(message: Message) {
        logger.debug(s"Enqueue HTTPS request to task: $message")
        messages += message
        if (!isRunning.get) {
          isRunning.set(true)
          executor.submit(new Runnable {
            def run = {
              while (!messages.isEmpty) {
                val message = messages.dequeue()
                logger.debug(s"Processing HTTPS request: $message, ${messages.size} pending...")
                handler.servletResponse = message.response
                channel.write(ChannelBuffers.copiedBuffer(message.proxyRequestBuffer))
              }
              isRunning.set(false)
            }
          })
        }
      }

      def channelConnected(processor: ⇒ Unit) = {
        if (channel.isConnected) processor
        else {
          channel.close()
          releaseTask(channelKey)
          writeErrorResponse(response)
        }
      }

    }

    def handler = new HttpsOutboundHandler() {
      override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
        releaseTask(channelKey)
      }
    }

    def connect {
      val task = new QueuedTask(channelKey, handler)
      tasks += channelKey -> task
      task channelConnected {
        response.setStatus(200)
        response.setContentType("application/octet-stream")
        response.setHeader(ResponseCompleted.name, "true")
        response.setContentLength(Utils.connectProxyResponse.getBytes("UTF-8").length)
        response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
        response.getOutputStream.flush()
      }
    }
    tasks.get(channelKey) match {
      case Some(task) ⇒ task.submit(Message(request, response, proxyRequestBuffer))
      case None       ⇒ connect
    }

  }
}

class HttpRequestProcessor(response: HttpServletResponse, proxyHost: Host, channelKey: ChannelKey, proxyRequestChannelBuffer: ChannelBuffer) {
  val clientBootstrap = newClientBootstrap
  clientBootstrap.setFactory(clientSocketChannelFactory)
  clientBootstrap.getPipeline.addLast("decoder", new HttpResponseDecoder(8192 * 2, 8192 * 4, 8192 * 4))
  clientBootstrap.getPipeline.addLast("handler", new HttpOutboundHandler(response))
  val channelFuture = clientBootstrap.connect(proxyHost.socketAddress).awaitUninterruptibly()

  def process {
    if (channelFuture.isSuccess() && channelFuture.getChannel.isConnected) {
      channelManager.add(channelKey, channelFuture.getChannel)
    } else {
      channelFuture.getChannel.close()
      writeErrorResponse(response)
      return
    }
    val channel = channelManager.get(channelKey).get
    channel.getPipeline.get(classOf[HttpOutboundHandler]).servletResponse = response
    if (channel.isConnected) {
      response.setStatus(HttpServletResponse.SC_OK)
      response.setHeader(ResponseCompleted.name, "false")
      response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
      response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED)
      //        response.setHeader(HttpHeaders.Names.TRAILER, "checksum")
      // Initiate chunked encoding by flushing the headers.
      response.getOutputStream.flush()

      //
      //        response.setStatus(200)
      //        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
      //        response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED)
      //        response.setHeader("checksum", "checksum")
      //        response.setContentLength(0)

      channel.write(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer))
    } else {
      channel.close()
      writeErrorResponse(response)
    }
  }
}

class SimpleHttpsRequestProcessor extends web.RequestProcessor {

  def releaseTask(channelKey: ChannelKey) {}

  def process(channelKey: ChannelKey, proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse) {

    channelManager.get(channelKey) match {
      case Some(channel) ⇒ {
        channel.getPipeline.get(classOf[HttpsOutboundHandler]).servletResponse = response
        if (channel.isConnected) {

          response.setStatus(HttpServletResponse.SC_OK)
          response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
          response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
          // Initiate chunked encoding by flushing the headers.
          response.getOutputStream.flush()

          channel.write(ChannelBuffers.copiedBuffer(proxyRequestBuffer))
        } else {
          //todo: error process
          channel.close()
          writeErrorResponse(response)
        }

      }
      case None ⇒ {
        val clientBootstrap = newClientBootstrap
        clientBootstrap.setFactory(clientSocketChannelFactory)
        clientBootstrap.getPipeline.addLast("handler", new HttpsOutboundHandler(response))
        val channelFuture = clientBootstrap.connect(channelKey.proxyHost.socketAddress).awaitUninterruptibly()
        val channel: Channel = channelFuture.getChannel
        //TODO:Update buffer size.
        channel.getConfig.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(DEFAULT_BUFFER_SIZE))
        if (channelFuture.isSuccess() && channel.isConnected) {
          channelManager.add(channelKey, channel)
          response.setStatus(200)
          response.setContentType("application/octet-stream")
          response.setHeader("response-completed", "true")
          response.setContentLength(Utils.connectProxyResponse.getBytes("UTF-8").length)
          response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
          response.getOutputStream.flush()
          return
        } else {
          //todo: error process
          channelFuture.getChannel.close()
          writeErrorResponse(response)
          return
        }

      }
    }

    //      if (HttpMethod.CONNECT.getName == request.getHeader("proxyRequestMethod")) {
    //        val clientBootstrap = newClientBootstrap
    //        clientBootstrap.setFactory(clientSocketChannelFactory)
    //        clientBootstrap.getPipeline.addLast("handler", new HttpsOutboundHandler(response))
    //        val channelFuture = clientBootstrap.connect(proxyHost.socketAddress).awaitUninterruptibly()
    //        val channel: Channel = channelFuture.getChannel
    //        //TODO:Update buffer size.
    //        channel.getConfig.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(1024 * 1024))
    //        if (channelFuture.isSuccess() && channel.isConnected) {
    //          channelManager.add(channelKey, channel)
    //          response.setStatus(200)
    //          response.setContentType("application/octet-stream")
    //          response.setHeader("response-completed", "true")
    //          response.setContentLength(Utils.connectProxyResponse.getBytes("UTF-8").length)
    //          response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
    //          response.getOutputStream.flush()
    //          return
    //        } else {
    //          //todo: error process
    //          channelFuture.getChannel.close()
    //          writeErrorResponse(response)
    //          return
    //        }
    //
    //      } else if ("HTTPS-DATA-TRANSFER" == request.getHeader("proxyRequestMethod")) {
    //
    //        val channel = channelManager.get(channelKey).get
    //        channel.getPipeline.get(classOf[HttpsOutboundHandler]).servletResponse = response
    //        if (channel.isConnected) {
    //
    //          response.setStatus(HttpServletResponse.SC_OK)
    //          response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    //          response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
    //          // Initiate chunked encoding by flushing the headers.
    //          response.getOutputStream.flush()
    //
    //          channel.write(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer))
    //        } else {
    //          //todo: error process
    //          channel.close()
    //          writeErrorResponse(response)
    //        }
    //
    //      }

  }

}

class NettyHttpRequestProcessor extends Logging {

  def releaseTask(channelKey: ChannelKey) {}

  def process(channelKey: ChannelKey, proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse) {
    {
      //    val response=asyncContext.getResponse.asInstanceOf[HttpServletResponse]
      //    val request=asyncContext.getRequest.asInstanceOf[HttpServletRequest]
      channelManager.get(channelKey) match {
        case Some(channel) ⇒ {
          //        channel.getPipeline.get(classOf[HttpsOutboundHandler]).servletResponse = response
          if (channel.isConnected) {
            //          def completeRequestIfNecessary(buffer:ChannelBuffer) {
            //            val isCloseRecord = buffer.readableBytes() > 5 && buffer.getByte(0) == 0x15 &&
            //              (buffer.getShort(3) + 5) == buffer.readableBytes()
            //
            //            if (isCloseRecord) {
            //             Try(channel.getPipeline.get(classOf[AsyncHttpsOutboundHandler]).asyncContext.complete())
            //              channelManager.remove(channelKey)
            //            }
            //          }

            val buffer = ChannelBuffers.copiedBuffer(proxyRequestBuffer)
            //          completeRequestIfNecessary(buffer)
            channel.write(buffer)
          } else {
            //todo: error process
            channel.close()
            writeErrorResponse(response)
          }

        }
        case None ⇒ connect(channelKey, proxyRequestBuffer)
      }

      //      if (HttpMethod.CONNECT.getName == request.getHeader("proxyRequestMethod")) {
      //        val clientBootstrap = newClientBootstrap
      //        clientBootstrap.setFactory(clientSocketChannelFactory)
      //        clientBootstrap.getPipeline.addLast("handler", new HttpsOutboundHandler(response))
      //        val channelFuture = clientBootstrap.connect(proxyHost.socketAddress).awaitUninterruptibly()
      //        val channel: Channel = channelFuture.getChannel
      //        //TODO:Update buffer size.
      //        channel.getConfig.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(1024 * 1024))
      //        if (channelFuture.isSuccess() && channel.isConnected) {
      //          channelManager.add(channelKey, channel)
      //          response.setStatus(200)
      //          response.setContentType("application/octet-stream")
      //          response.setHeader("response-completed", "true")
      //          response.setContentLength(Utils.connectProxyResponse.getBytes("UTF-8").length)
      //          response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
      //          response.getOutputStream.flush()
      //          return
      //        } else {
      //          //todo: error process
      //          channelFuture.getChannel.close()
      //          writeErrorResponse(response)
      //          return
      //        }
      //
      //      } else if ("HTTPS-DATA-TRANSFER" == request.getHeader("proxyRequestMethod")) {
      //
      //        val channel = channelManager.get(channelKey).get
      //        channel.getPipeline.get(classOf[HttpsOutboundHandler]).servletResponse = response
      //        if (channel.isConnected) {
      //
      //          response.setStatus(HttpServletResponse.SC_OK)
      //          response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
      //          response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
      //          // Initiate chunked encoding by flushing the headers.
      //          response.getOutputStream.flush()
      //
      //          channel.write(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer))
      //        } else {
      //          //todo: error process
      //          channel.close()
      //          writeErrorResponse(response)
      //        }
      //
      //      }

    }
  }

  def connect(channelKey: ChannelKey, proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse) {
    val asyncContext = request.startAsync()
    asyncContext.setTimeout(0)
    asyncContext.start(new Runnable {
      def run() {
        val clientBootstrap = newClientBootstrap
        clientBootstrap.setFactory(clientSocketChannelFactory)
        clientBootstrap.getPipeline.addLast("handler", new AsyncHttpsOutboundHandler(asyncContext))
        val channelFuture = clientBootstrap.connect(channelKey.proxyHost.socketAddress).awaitUninterruptibly()
        val channel: Channel = channelFuture.getChannel
        //TODO:Update buffer size.
        //        channel.getConfig.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(DEFAULT_BUFFER_SIZE))
        if (channelFuture.isSuccess() && channel.isConnected) {
          channelManager.add(channelKey, channel)
          channel.setAttachment(channelKey)
          response.setStatus(HttpServletResponse.SC_OK)
          response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
          response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
          response.setHeader(ResponseCompleted.name, "true")
          // Initiate chunked encoding by flushing the headers.
          response.getOutputStream.flush()

          logger.debug(s"Writing proxy request to $channel \n ${Utils.hexDumpToString(proxyRequestBuffer)}")
          channel.write(ChannelBuffers.wrappedBuffer(proxyRequestBuffer))
        } else {
          //todo: error process
          channelFuture.getChannel.close()
          writeErrorResponse(response)
          asyncContext.complete()
        }
      }
    })
  }
}

class NettyHttpsRequestProcessor extends Logging {

  def releaseTask(channelKey: ChannelKey) {}

  def process(channelKey: ChannelKey, proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse) {
    //    val response=asyncContext.getResponse.asInstanceOf[HttpServletResponse]
    //    val request=asyncContext.getRequest.asInstanceOf[HttpServletRequest]
    channelManager.get(channelKey) match {
      case Some(channel) ⇒ {
        //        channel.getPipeline.get(classOf[HttpsOutboundHandler]).servletResponse = response
        if (channel.isConnected) {
          //          def completeRequestIfNecessary(buffer:ChannelBuffer) {
          //            val isCloseRecord = buffer.readableBytes() > 5 && buffer.getByte(0) == 0x15 &&
          //              (buffer.getShort(3) + 5) == buffer.readableBytes()
          //
          //            if (isCloseRecord) {
          //             Try(channel.getPipeline.get(classOf[AsyncHttpsOutboundHandler]).asyncContext.complete())
          //              channelManager.remove(channelKey)
          //            }
          //          }

          val buffer = ChannelBuffers.copiedBuffer(proxyRequestBuffer)
          //          completeRequestIfNecessary(buffer)
          channel.write(buffer)
        } else {
          //todo: error process
          channel.close()
          writeErrorResponse(response)
        }

      }
      case None ⇒ connect(request, channelKey, response)
    }

    //      if (HttpMethod.CONNECT.getName == request.getHeader("proxyRequestMethod")) {
    //        val clientBootstrap = newClientBootstrap
    //        clientBootstrap.setFactory(clientSocketChannelFactory)
    //        clientBootstrap.getPipeline.addLast("handler", new HttpsOutboundHandler(response))
    //        val channelFuture = clientBootstrap.connect(proxyHost.socketAddress).awaitUninterruptibly()
    //        val channel: Channel = channelFuture.getChannel
    //        //TODO:Update buffer size.
    //        channel.getConfig.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(1024 * 1024))
    //        if (channelFuture.isSuccess() && channel.isConnected) {
    //          channelManager.add(channelKey, channel)
    //          response.setStatus(200)
    //          response.setContentType("application/octet-stream")
    //          response.setHeader("response-completed", "true")
    //          response.setContentLength(Utils.connectProxyResponse.getBytes("UTF-8").length)
    //          response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
    //          response.getOutputStream.flush()
    //          return
    //        } else {
    //          //todo: error process
    //          channelFuture.getChannel.close()
    //          writeErrorResponse(response)
    //          return
    //        }
    //
    //      } else if ("HTTPS-DATA-TRANSFER" == request.getHeader("proxyRequestMethod")) {
    //
    //        val channel = channelManager.get(channelKey).get
    //        channel.getPipeline.get(classOf[HttpsOutboundHandler]).servletResponse = response
    //        if (channel.isConnected) {
    //
    //          response.setStatus(HttpServletResponse.SC_OK)
    //          response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    //          response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
    //          // Initiate chunked encoding by flushing the headers.
    //          response.getOutputStream.flush()
    //
    //          channel.write(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer))
    //        } else {
    //          //todo: error process
    //          channel.close()
    //          writeErrorResponse(response)
    //        }
    //
    //      }

  }

  def connect(request: HttpServletRequest, channelKey: ChannelKey, response: HttpServletResponse) {
    val asyncContext = request.startAsync()
    asyncContext.setTimeout(0)
    asyncContext.start(new Runnable {
      def run() {
        val clientBootstrap = newClientBootstrap
        clientBootstrap.setFactory(clientSocketChannelFactory)
        clientBootstrap.getPipeline.addLast("handler", new AsyncHttpsOutboundHandler(asyncContext))
        val channelFuture = clientBootstrap.connect(channelKey.proxyHost.socketAddress).awaitUninterruptibly()
        val channel: Channel = channelFuture.getChannel
        //TODO:Update buffer size.
        //        channel.getConfig.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(DEFAULT_BUFFER_SIZE))
        if (channelFuture.isSuccess() && channel.isConnected) {
          channelManager.add(channelKey, channel)
          channel.setAttachment(channelKey)
          response.setStatus(HttpServletResponse.SC_OK)
          response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
          response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
          response.setHeader(ResponseCompleted.name, "true")
          // Initiate chunked encoding by flushing the headers.
          response.getOutputStream.flush()

          logger.debug(s"[${request.getSession(false).getId}] - Writing connection established response")
          response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
          response.getOutputStream.flush()
        } else {
          //todo: error process
          channelFuture.getChannel.close()
          writeErrorResponse(response)
          asyncContext.complete()
        }
      }
    })
  }
}

class AsyncHttpsOutboundHandler(val asyncContext: AsyncContext) extends SimpleChannelUpstreamHandler with Logging {
  val response = asyncContext.getResponse.asInstanceOf[HttpServletResponse]
  val request = asyncContext.getRequest.asInstanceOf[HttpServletRequest]
  //  val finishByte=Array[Byte](0x14, ox03,0x01,0x00 01 01)
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.debug(s"[${request.getSession(false).getId}] - Receive message:\n ${Utils.formatMessage(e.getMessage)}")
    //        response.setStatus(HttpServletResponse.SC_OK)
    //        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    //        response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
    //        response.getOutputStream.flush

    e.getMessage match {
      case buffer: ChannelBuffer ⇒ {
        response.getOutputStream.write(buffer.array())
        Try(response.getOutputStream.flush) match {
          case Success(e) ⇒
          case Failure(e) ⇒ logger.error(s"Write data error: ${Utils.formatMessage(e.getMessage)} ", e)
        }
      }
      case _ ⇒
    }

    //    data match {
    //      case None ⇒ {
    //        var dataLength: Int = 0
    //        var sslRecord = ByteBuffer.allocateDirect(5)
    //        buffer.readBytes(sslRecord)
    //        sslRecord.flip()
    //
    //        dataLength = sslRecord.remaining()
    //        //Store sslRecord first
    //        var sentBuffer: ChannelBuffer = ChannelBuffers.copiedBuffer(sslRecord)
    //        //          if(sslRecord.get()==0x14 && buffer.readableBytes()>0){//Server Done and Verify data record.
    //        //          val serverDoneLength=sslRecord.getShort(3)
    //        //            val serverDoneData=new Array[Byte](serverDoneLength)
    //        //            buffer.readBytes(serverDoneData)
    //        //            sentBuffer=ChannelBuffers.wrappedBuffer(sentBuffer,ChannelBuffers.copiedBuffer(serverDoneData))
    //        //            dataLength+=serverDoneLength
    //        //
    //        //            sslRecord.position(0)
    //        //            buffer.readBytes(sslRecord)
    //        //            sslRecord.flip()
    //        //            dataLength += sslRecord.remaining()
    //        //            sentBuffer=ChannelBuffers.wrappedBuffer(sentBuffer,ChannelBuffers.copiedBuffer(sslRecord))
    //        //          }
    //
    //        //The continuous data length
    //        dataLength += sslRecord.getShort(3)
    //
    //        sentBuffer = ChannelBuffers.wrappedBuffer(sentBuffer, buffer)
    //
    //        synchronized(data = Some(DataHolder(dataLength, sentBuffer)))
    //      }
    //      case Some(holder) ⇒ holder.buffer = ChannelBuffers.wrappedBuffer(holder.buffer, buffer)
    //    }
    //    data foreach {
    //      send ⇒
    //        if (send.ready) {
    //          logger.debug(s"Write response buffer\n${Utils.formatMessage(ChannelBuffers.copiedBuffer(send.buffer))}")
    //          //        response.setStatus(HttpServletResponse.SC_OK)
    //          //        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    //          //        response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
    //          //        response.getOutputStream.flush
    //          servletResponse.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    //          servletResponse.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
    //          servletResponse.setContentLength(send.contentLength)
    //          servletResponse.setHeader(ResponseCompleted.name, "true")
    //          send.buffer.readBytes(servletResponse.getOutputStream, send.contentLength)
    //          servletResponse.getOutputStream.flush
    //          synchronized(data = None)
    //
    //          //        response.setStatus(200)
    //          //        response.setContentType("application/octet-stream")
    //          //        response.setContentLength(Utils.connectProxyResponse.getBytes("UTF-8").length)
    //          //        response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
    //          //        response.getOutputStream.flush()
    //
    //          //        response.setContentLength(0)
    //          //        response.getOutputStream.write(Array[Byte]())
    //          //        response.getOutputStream.flush
    //
    //          //        response.setStatus(200)
    //          //        response.setContentType("application/octet-stream")
    //          //        response.setContentLength(0)
    //          //        response.getOutputStream.flush()
    //        }
    //
    //    }

    //  data match {
    //    case None => {
    //      var dataLength: Int =0
    //      var sslRecord = ByteBuffer.allocateDirect(5)
    //      buffer.readBytes(sslRecord)
    //      sslRecord.flip()
    //      dataLength= sslRecord.remaining()
    //      //Store sslRecord first
    //      var sentBuffer: ChannelBuffer=ChannelBuffers.copiedBuffer(sslRecord)
    //      if(sslRecord.get()==0x14 && buffer.readableBytes()>0){//Server Done and Verify data record.
    //      val serverDoneLength=sslRecord.getShort(3)
    //        val serverDoneData=new Array[Byte](serverDoneLength)
    //        buffer.readBytes(serverDoneData)
    //        sentBuffer=ChannelBuffers.wrappedBuffer(sentBuffer,ChannelBuffers.copiedBuffer(serverDoneData))
    //        dataLength+=serverDoneLength
    //
    //        sslRecord.position(0)
    //        buffer.readBytes(sslRecord)
    //        sslRecord.flip()
    //        dataLength += sslRecord.remaining()
    //        sentBuffer=ChannelBuffers.wrappedBuffer(sentBuffer,ChannelBuffers.copiedBuffer(sslRecord))
    //      }
    //
    //      //The continuous data length
    //      dataLength += sslRecord.getShort(3)
    //
    //      sentBuffer=ChannelBuffers.wrappedBuffer(sentBuffer, buffer)
    //
    //      synchronized(data=Some(DataHolder(dataLength,sentBuffer)))
    //    }
    //    case Some(holder) =>holder.buffer=ChannelBuffers.wrappedBuffer(holder.buffer,buffer)
    //  }
    //  data foreach{
    //    send => if  (send.ready) {
    //      logger.debug(s"Write response buffer\n${Utils.formatMessage(ChannelBuffers.copiedBuffer(send.buffer))}")
    //      //        response.setStatus(HttpServletResponse.SC_OK)
    //      //        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    //      //        response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
    //      //        response.getOutputStream.flush
    //      response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    //      response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
    //      response.setContentLength(send.length)
    //      response.setHeader(ResponseCompleted.name, "true")
    //      send.buffer.readBytes(response.getOutputStream, send.length)
    //      response.getOutputStream.flush
    //      synchronized(data=None)
    //
    //      //        response.setStatus(200)
    //      //        response.setContentType("application/octet-stream")
    //      //        response.setContentLength(Utils.connectProxyResponse.getBytes("UTF-8").length)
    //      //        response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
    //      //        response.getOutputStream.flush()
    //
    //
    //
    //      //        response.setContentLength(0)
    //      //        response.getOutputStream.write(Array[Byte]())
    //      //        response.getOutputStream.flush
    //
    //      //        response.setStatus(200)
    //      //        response.setContentType("application/octet-stream")
    //      //        response.setContentLength(0)
    //      //        response.getOutputStream.flush()
    //    }
    //
    //  }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn(s"Got exception on ${ctx.getChannel}", e.getCause)
    e.getChannel.close()
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.warn(s"Got closed event on ${e.getChannel}, complete request now and remove channel from ChannelManager.")
    channelManager.remove(e.getChannel.getAttachment.asInstanceOf[ChannelKey])
    Try(asyncContext.complete())
  }
}

class HttpsOutboundHandler(var servletResponse: HttpServletResponse = null) extends SimpleChannelUpstreamHandler with Logging {

  var data: Option[DataHolder] = None

  //  val finishByte=Array[Byte](0x14, ox03,0x01,0x00 01 01)
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.debug(s"############${e.getChannel} receive message###############\n ${Utils.formatMessage(e.getMessage)}")
    val buffer: ChannelBuffer = e.getMessage.asInstanceOf[ChannelBuffer]

    data match {
      case None ⇒ {
        var dataLength: Int = 0
        var sslRecord = ByteBuffer.allocateDirect(5)
        buffer.readBytes(sslRecord)
        sslRecord.flip()

        dataLength = sslRecord.remaining()
        //Store sslRecord first
        var sentBuffer: ChannelBuffer = ChannelBuffers.copiedBuffer(sslRecord)
        //          if(sslRecord.get()==0x14 && buffer.readableBytes()>0){//Server Done and Verify data record.
        //          val serverDoneLength=sslRecord.getShort(3)
        //            val serverDoneData=new Array[Byte](serverDoneLength)
        //            buffer.readBytes(serverDoneData)
        //            sentBuffer=ChannelBuffers.wrappedBuffer(sentBuffer,ChannelBuffers.copiedBuffer(serverDoneData))
        //            dataLength+=serverDoneLength
        //
        //            sslRecord.position(0)
        //            buffer.readBytes(sslRecord)
        //            sslRecord.flip()
        //            dataLength += sslRecord.remaining()
        //            sentBuffer=ChannelBuffers.wrappedBuffer(sentBuffer,ChannelBuffers.copiedBuffer(sslRecord))
        //          }

        //The continuous data length
        dataLength += sslRecord.getShort(3)

        sentBuffer = ChannelBuffers.wrappedBuffer(sentBuffer, buffer)

        synchronized(data = Some(DataHolder(dataLength, sentBuffer)))
      }
      case Some(holder) ⇒ holder.buffer = ChannelBuffers.wrappedBuffer(holder.buffer, buffer)
    }
    data foreach {
      send ⇒
        if (send.ready) {
          logger.debug(s"Write response buffer\n${Utils.formatMessage(ChannelBuffers.copiedBuffer(send.buffer))}")
          //        response.setStatus(HttpServletResponse.SC_OK)
          //        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
          //        response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
          //        response.getOutputStream.flush
          servletResponse.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
          servletResponse.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
          servletResponse.setContentLength(send.contentLength)
          servletResponse.setHeader(ResponseCompleted.name, "true")
          send.buffer.readBytes(servletResponse.getOutputStream, send.contentLength)
          servletResponse.getOutputStream.flush
          synchronized(data = None)

          //        response.setStatus(200)
          //        response.setContentType("application/octet-stream")
          //        response.setContentLength(Utils.connectProxyResponse.getBytes("UTF-8").length)
          //        response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
          //        response.getOutputStream.flush()

          //        response.setContentLength(0)
          //        response.getOutputStream.write(Array[Byte]())
          //        response.getOutputStream.flush

          //        response.setStatus(200)
          //        response.setContentType("application/octet-stream")
          //        response.setContentLength(0)
          //        response.getOutputStream.flush()
        }

    }

    //  data match {
    //    case None => {
    //      var dataLength: Int =0
    //      var sslRecord = ByteBuffer.allocateDirect(5)
    //      buffer.readBytes(sslRecord)
    //      sslRecord.flip()
    //      dataLength= sslRecord.remaining()
    //      //Store sslRecord first
    //      var sentBuffer: ChannelBuffer=ChannelBuffers.copiedBuffer(sslRecord)
    //      if(sslRecord.get()==0x14 && buffer.readableBytes()>0){//Server Done and Verify data record.
    //      val serverDoneLength=sslRecord.getShort(3)
    //        val serverDoneData=new Array[Byte](serverDoneLength)
    //        buffer.readBytes(serverDoneData)
    //        sentBuffer=ChannelBuffers.wrappedBuffer(sentBuffer,ChannelBuffers.copiedBuffer(serverDoneData))
    //        dataLength+=serverDoneLength
    //
    //        sslRecord.position(0)
    //        buffer.readBytes(sslRecord)
    //        sslRecord.flip()
    //        dataLength += sslRecord.remaining()
    //        sentBuffer=ChannelBuffers.wrappedBuffer(sentBuffer,ChannelBuffers.copiedBuffer(sslRecord))
    //      }
    //
    //      //The continuous data length
    //      dataLength += sslRecord.getShort(3)
    //
    //      sentBuffer=ChannelBuffers.wrappedBuffer(sentBuffer, buffer)
    //
    //      synchronized(data=Some(DataHolder(dataLength,sentBuffer)))
    //    }
    //    case Some(holder) =>holder.buffer=ChannelBuffers.wrappedBuffer(holder.buffer,buffer)
    //  }
    //  data foreach{
    //    send => if  (send.ready) {
    //      logger.debug(s"Write response buffer\n${Utils.formatMessage(ChannelBuffers.copiedBuffer(send.buffer))}")
    //      //        response.setStatus(HttpServletResponse.SC_OK)
    //      //        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    //      //        response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
    //      //        response.getOutputStream.flush
    //      response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    //      response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
    //      response.setContentLength(send.length)
    //      response.setHeader(ResponseCompleted.name, "true")
    //      send.buffer.readBytes(response.getOutputStream, send.length)
    //      response.getOutputStream.flush
    //      synchronized(data=None)
    //
    //      //        response.setStatus(200)
    //      //        response.setContentType("application/octet-stream")
    //      //        response.setContentLength(Utils.connectProxyResponse.getBytes("UTF-8").length)
    //      //        response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
    //      //        response.getOutputStream.flush()
    //
    //
    //
    //      //        response.setContentLength(0)
    //      //        response.getOutputStream.write(Array[Byte]())
    //      //        response.getOutputStream.flush
    //
    //      //        response.setStatus(200)
    //      //        response.setContentType("application/octet-stream")
    //      //        response.setContentLength(0)
    //      //        response.getOutputStream.flush()
    //    }
    //
    //  }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn(s"Got exception on ${ctx.getChannel}", e.getCause)
    e.getChannel.close()
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.warn("###################Got closed event on : %s".format(e.getChannel))
  }
}

class HttpOutboundHandler(var servletResponse: HttpServletResponse) extends SimpleChannelUpstreamHandler with Logging {
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.debug(s"############${e.getChannel} receive message###############\n${Utils.formatMessage(e.getMessage)}")
    def encodeHttpResponse(response: Any): ChannelBuffer = {
      val encode = classOf[HttpResponseEncoder].getSuperclass.getDeclaredMethods.filter(_.getName == "encode")(0)
      encode.setAccessible(true)
      encode.invoke(new HttpResponseEncoder(), null, ctx.getChannel, e.getMessage).asInstanceOf[ChannelBuffer]
    }
    e.getMessage match {
      case response: HttpResponse if !response.isChunked ⇒ {
        servletResponse.setHeader(ResponseCompleted.name, "true")
        val responseBuffer = encodeHttpResponse(response)
        servletResponse.setContentLength(responseBuffer.readableBytes())
        responseBuffer.readBytes(servletResponse.getOutputStream, responseBuffer.readableBytes)
        servletResponse.getOutputStream.flush
        Utils.closeChannel(e.getChannel)
      }
      case response: HttpResponse if response.isChunked ⇒ {
        servletResponse.setHeader(ResponseCompleted.name, "false")
        val responseBuffer = encodeHttpResponse(response)
        responseBuffer.readBytes(servletResponse.getOutputStream, responseBuffer.readableBytes)
        servletResponse.getOutputStream.flush
      }
      case chunk: HttpChunk if !chunk.isLast ⇒ {
        servletResponse.setHeader(ResponseCompleted.name, "false")
        val responseBuffer = encodeHttpResponse(chunk)
        responseBuffer.readBytes(servletResponse.getOutputStream, responseBuffer.readableBytes)
        servletResponse.getOutputStream.flush
      }
      case chunk: HttpChunk if chunk.isLast ⇒ {
        servletResponse.setHeader(ResponseCompleted.name, "true")
        servletResponse.setHeader("isLastChunk", "true")
        val responseBuffer = encodeHttpResponse(chunk)
        servletResponse.setContentLength(responseBuffer.readableBytes())
        responseBuffer.readBytes(servletResponse.getOutputStream, responseBuffer.readableBytes)
        servletResponse.getOutputStream.flush
        Utils.closeChannel(e.getChannel)
      }
      case _ ⇒
    }

    //    response.setHeader(ResponseCompleted.name, "true")
    //    response.setHeader(ResponseCompleted.name, "true")
    //    response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, "")
    //    response.setContentType("application/octet-stream")
    //    response.setContentLength(1)
    //    response.getOutputStream.write(0x00)
    //    response.getOutputStream.flush()
    //    response.getOutputStream.write(0)
    //    response.getOutputStream.write(HttpConstants.CR)
    //    response.getOutputStream.write(HttpConstants.LF)

    //    response.setHeader(ResponseCompleted.name, "true")
    //    response.setContentType("application/octet-stream")
    //    response.setContentLength("chunked".getBytes().length)
    //    response.getOutputStream.write("chunked".getBytes())

    //    response.setStatus(200)
    //    response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    //    response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED)
    //    response.setHeader(HttpHeaders.Names.TRAILER, "checksum")
    //    response.setHeader("checksum", "checksum")
    //    response.setContentLength(0)

    //    response.setContentType("text/plain")
    //    response.setContentLength("HTTP/1.1 400 Can't establish connection\r\n\r\n".getBytes("UTF-8").length)
    //    response.getOutputStream.write("HTTP/1.1 400 Can't establish connection\r\n\r\n".getBytes("UTF-8"))

    //    output.writeBytes("HTTP/1.1 200 OK");
    //    addCRLR(output);
    //    output.writeBytes("Content-type: text/plain");
    //    addCRLR(output);
    //    output.writeBytes("Transfer-encoding: chunked");
    //    addCRLR(output);
    //    output.writeBytes("Trailer: checksum");

  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.debug("Got closed event on : %s".format(e.getChannel))
    //    response.setStatus(200)
    //    response.setHeader(ResponseCompleted.name, "true")
    //    response.setContentType("application/octet-stream")
    //    response.setContentLength(0)
    //    response.getOutputStream.flush()
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn("Caught exception on : %s".format(e.getChannel), e.getCause)
    e.getCause match {
      case closeException: ClosedChannelException ⇒ //Just ignore it
      case exception                              ⇒ Utils.closeChannel(e.getChannel)
    }
  }
}

