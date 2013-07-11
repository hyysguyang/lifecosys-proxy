package com.lifecosys.toolkit.proxy

import javax.servlet.http.{ HttpServletResponse, HttpServletRequest, HttpServlet }
import org.jboss.netty.channel._
import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.buffer.{ ChannelBufferInputStream, ChannelBufferOutputStream, ChannelBuffers, ChannelBuffer }
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import java.util.concurrent.Executors
import org.jboss.netty.handler.codec.http._
import org.apache.commons.io.IOUtils
import org.jboss.netty.handler.logging.LoggingHandler
import org.jboss.netty.logging.{ Slf4JLoggerFactory, InternalLoggerFactory }
import javax.servlet.{ ServletOutputStream, AsyncContext }
import org.littleshoot.proxy.{ ProxyUtils, ProxyHttpResponseEncoder }
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.io.{ DefaultHttpRequestParser, HttpTransportMetricsImpl, SessionInputBufferImpl }
import org.apache.http.client.config.RequestConfig
import com.typesafe.scalalogging.slf4j.Logging
import org.apache.commons.lang3.StringUtils

/**
 *
 *
 * @author Young Gu
 * @version 1.0 6/21/13 10:28 AM
 */

case class ChannelKey(sessionId: String, host: Host)

class ChannelManager {
  private[this] val channels = scala.collection.mutable.Map[ChannelKey, Channel]()
  def get(channelKey: ChannelKey) = channels.get(channelKey)
  def add(channelKey: ChannelKey, channel: Channel) = channels += channelKey -> channel
}

object DefaultChannelManager extends ChannelManager

class HttpsOutboundHandler(var response: HttpServletResponse) extends SimpleChannelUpstreamHandler with Logging {
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.debug(s"############${e.getChannel} receive message###############\n ${Utils.formatMessage(e.getMessage)}")
    val buffer: ChannelBuffer = e.getMessage.asInstanceOf[ChannelBuffer]
    synchronized {
      buffer.readBytes(response.getOutputStream, buffer.readableBytes)
      response.getOutputStream.flush
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    e.getCause.printStackTrace()
    e.getChannel.close
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.warn("###################Got closed event on : %s".format(e.getChannel))
  }
}

class HttpOutboundHandler(var response: HttpServletResponse) extends SimpleChannelUpstreamHandler with Logging {
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.debug(s"############${e.getChannel} receive message###############\n${Utils.formatMessage(e.getMessage)}")
    val responseCompleted = e.getMessage match {
      case response: HttpResponse if !response.isChunked ⇒ true
      case chunk: HttpChunk if chunk.isLast ⇒ true
      case _ ⇒ false
    }
    response.setHeader("response-completed", responseCompleted.toString)

    val encode = classOf[HttpResponseEncoder].getSuperclass.getDeclaredMethods.filter(_.getName == "encode")(0)
    encode.setAccessible(true)
    val responseBuffer = encode.invoke(new ProxyHttpResponseEncoder(), null, ctx.getChannel, e.getMessage).asInstanceOf[ChannelBuffer]
    responseBuffer.readBytes(response.getOutputStream, responseBuffer.readableBytes)
    response.getOutputStream.flush
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    e.getCause.printStackTrace()
    e.getChannel.close
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.warn("###################Got closed event on : %s".format(e.getChannel))
  }
}
object ProxyServlet {
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
}

class ProxyServlet extends HttpServlet with Logging {
  val executor = Executors.newCachedThreadPool()
  val clientSocketChannelFactory = new NioClientSocketChannelFactory(executor, executor)

  override def service(request: HttpServletRequest, response: HttpServletResponse) {
    if (StringUtils.isEmpty(request.getRequestedSessionId) && request.getSession(false) == null) {
      request.getSession(true)
      logger.debug(s"Created session: ${request.getSession.getId} for request: ${request}")
    }

    require(StringUtils.isNotEmpty(request.getSession.getId), "Session have not been created, server error.")

    val proxyRequestChannelBuffer = ChannelBuffers.dynamicBuffer(512)
    val bufferStream = new ChannelBufferOutputStream(proxyRequestChannelBuffer)
    IOUtils.copy(request.getInputStream, bufferStream)
    IOUtils.closeQuietly(bufferStream)

    logger.debug(s"############Process payload ###############\n${Utils.formatMessage(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer))}")

    val proxyHost = Host(request.getHeader("proxyHost"))
    val channelKey: ChannelKey = ChannelKey(request.getSession.getId, proxyHost)

    if (HttpMethod.CONNECT.getName != request.getHeader("proxyRequestMethod") && "HTTPS-DATA-TRANSFER" != request.getHeader("proxyRequestMethod")) {
      new HttpRequestProcessor(response, proxyHost, channelKey, proxyRequestChannelBuffer).process
    } else {
      new HttpsRequestProcessor().processHttps(request, response, proxyHost, channelKey, proxyRequestChannelBuffer)
    }
  }

  class HttpsRequestProcessor {

    def processHttps(request: HttpServletRequest, response: HttpServletResponse, proxyHost: Host, channelKey: ChannelKey, proxyRequestChannelBuffer: ChannelBuffer) {
      if (HttpMethod.CONNECT.getName == request.getHeader("proxyRequestMethod")) {
        val clientBootstrap = newClientBootstrap
        clientBootstrap.setFactory(clientSocketChannelFactory)
        clientBootstrap.getPipeline.addLast("handler", new HttpsOutboundHandler(response))
        val channelFuture = clientBootstrap.connect(proxyHost.socketAddress).awaitUninterruptibly()
        if (channelFuture.isSuccess() && channelFuture.getChannel.isConnected) {
          DefaultChannelManager.add(channelKey, channelFuture.getChannel)
          response.setStatus(200)
          response.setContentType("application/octet-stream")
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

      } else if ("HTTPS-DATA-TRANSFER" == request.getHeader("proxyRequestMethod")) {

        val channel = DefaultChannelManager.get(channelKey).get
        channel.getPipeline.get(classOf[HttpsOutboundHandler]).response = response
        if (channel.isConnected) {

          response.setStatus(HttpServletResponse.SC_OK)
          response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
          response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
          // Initiate chunked encoding by flushing the headers.
          response.getOutputStream.flush()

          channel.write(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer))
        } else {
          //todo: error process
          channel.close()
          writeErrorResponse(response)
        }

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
        DefaultChannelManager.add(channelKey, channelFuture.getChannel)
      } else {
        channelFuture.getChannel.close()
        writeErrorResponse(response)
        return
      }
      val channel = DefaultChannelManager.get(channelKey).get
      channel.getPipeline.get(classOf[HttpOutboundHandler]).response = response
      if (channel.isConnected) {
        response.setStatus(HttpServletResponse.SC_OK)
        response.setHeader("response-completed", "false")
        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
        response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
        // Initiate chunked encoding by flushing the headers.
        response.getOutputStream.flush()

        channel.write(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer))
      } else {
        channel.close()
        writeErrorResponse(response)
      }
    }
  }

  def writeErrorResponse(response: HttpServletResponse) {
    response.setHeader("response-completed", "true")
    response.setStatus(200)
    response.setContentType("application/octet-stream")
    response.setContentLength("HTTP/1.1 400 Can't establish connection\r\n\r\n".getBytes("UTF-8").length)
    response.getOutputStream.write("HTTP/1.1 400 Can't establish connection\r\n\r\n".getBytes("UTF-8"))
    response.getOutputStream.flush()
  }

}
