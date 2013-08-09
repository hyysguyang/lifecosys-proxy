package com.lifecosys.toolkit.proxy.web

import com.typesafe.scalalogging.slf4j.Logging
import com.lifecosys.toolkit.proxy._
import javax.servlet.http.{ HttpServletResponse, HttpServletRequest }
import java.io.InputStream
import java.nio.ByteBuffer
import org.jboss.netty.channel.{ ChannelStateEvent, ChannelHandlerContext, FixedReceiveBufferSizePredictorFactory, Channel }
import org.jboss.netty.handler.codec.http.{ HttpResponseDecoder, HttpHeaders }
import com.lifecosys.toolkit.proxy.ChannelKey
import scala.Some
import org.jboss.netty.buffer.{ ChannelBuffer, ChannelBuffers }
import java.util.concurrent.atomic.AtomicBoolean
import com.lifecosys.toolkit.proxy.web.netty.{ HttpsOutboundHandler, HttpOutboundHandler }

/**
 *
 *
 * @author Young Gu
 * @version 1.0 8/9/13 1:25 PM
 */
trait RequestProcessor extends Logging {
  def process(channelKey: ChannelKey, proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse)

  def readDataRecord(message: Message, socketInput: InputStream): Array[Byte] = {
    try {
      val buffer = new Array[Byte](5)
      var size = socketInput.read(buffer)
      while (size != 5) {
        size += socketInput.read(buffer, size, 5 - size)
      }

      logger.error(s"[${Thread.currentThread()} | ${message.request.getSession.getId}] - Received data header: ${Utils.hexDumpToString(buffer)}")

      val byteBuffer = new Array[Byte](5)
      buffer.copyToArray(byteBuffer)
      val dataLength = ByteBuffer.wrap(byteBuffer).getShort(3)
      logger.error(s"[${Thread.currentThread()} | ${message.request.getSession.getId}]###########5 + dataLength###################${5 + dataLength}")
      val data = new Array[Byte](5 + dataLength)
      buffer.copyToArray(data)
      size = socketInput.read(data, 5, dataLength)
      logger.error(s"[${Thread.currentThread()} | ${message.request.getSession.getId}] - Reading data: $size")
      while (size != dataLength) {
        val length = socketInput.read(data, 5 + size, dataLength - size)
        logger.error(s"[${Thread.currentThread()} | ${message.request.getSession.getId}] - Reading data: size=$size, dataLength=$dataLength, length=$length")
        if (length > 0) {
          size += length
        } else {
          logger.error("Thread.sleep(100000) Thread.sleep(100000) Thread.sleep(100000) ")
          Thread.sleep(100000)
        }
      }

      //        logger.debug(s"[${message.request.getSession.getId} | $socket] - Reading record completed: ${Utils.hexDumpToString(data)}")
      logger.error(s"[${Thread.currentThread()} | ${message.request.getSession.getId}] - Reading record completed: ${data.length}")

      return data
    } catch {
      case e: Throwable ⇒ {
        logger.error("Error", e)
        logger.error("Error")
      }
    }
    new Array[Byte](0)
  }

}

case class Message(request: HttpServletRequest, response: HttpServletResponse, proxyRequestBuffer: Array[Byte])

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

abstract class BaseHttpsRequestProcessor extends RequestProcessor {
  val tasks = scala.collection.mutable.Map[ChannelKey, Task]()
  def releaseTask(channelKey: ChannelKey)

}

class SimpleHttpsRequestProcessor extends BaseHttpsRequestProcessor {

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
