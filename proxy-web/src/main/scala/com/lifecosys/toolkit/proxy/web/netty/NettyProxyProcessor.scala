package com.lifecosys.toolkit.proxy.web.netty

import com.lifecosys.toolkit.proxy._
import org.jboss.netty.channel._
import org.jboss.netty.buffer.{ ChannelBuffers, ChannelBuffer }
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import com.typesafe.scalalogging.slf4j.Logging
import com.lifecosys.toolkit.proxy.web._
import javax.servlet.AsyncContext
import scala.util.Try
import org.jboss.netty.handler.timeout.{ IdleStateEvent, IdleStateAwareChannelHandler, IdleStateHandler }
import org.jboss.netty.handler.codec.http._
import com.lifecosys.toolkit.proxy.ChannelKey

/**
 *
 *
 * @author Young Gu
 * @version 1.0 8/9/13 1:40 PM
 */

sealed trait NettyTaskSupport {

  type Task = (AsyncContext) ⇒ Unit

  def starTask(request: HttpServletRequest)(task: Task) {
    val asyncContext = request.startAsync()
    asyncContext.setTimeout(240 * 1000)
    asyncContext.start(new Runnable {
      def run() {
        task(asyncContext)
      }
    })
  }

  def createConnection(asyncContext: AsyncContext)(connectedCallback: (Channel) ⇒ Unit) = {
    val request = asyncContext.getRequest.asInstanceOf[HttpServletRequest]
    val response = asyncContext.getResponse.asInstanceOf[HttpServletResponse]
    val channelFuture = connect(asyncContext, parseChannelKey(request))
    val channel: Channel = channelFuture.getChannel
    channel.getConfig.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(DEFAULT_BUFFER_SIZE))
    if (channelFuture.isSuccess() && channel.isConnected) {
      request.getSession(false).setAttribute(SESSION_KEY_ENDPOINT, channel)
      initializeChunkedResponse(response)
      connectedCallback(channel)
    } else {
      //todo: error process
      channelFuture.getChannel.close()
      writeErrorResponse(response)
      asyncContext.complete()
    }

  }

  def connect(asyncContext: AsyncContext, channelKey: ChannelKey) = {
    val clientBootstrap = newClientBootstrap
    clientBootstrap.setFactory(clientSocketChannelFactory)
    clientBootstrap setPipelineFactory pipelineFactory(asyncContext)
    clientBootstrap.connect(channelKey.proxyHost.socketAddress).awaitUninterruptibly()
  }

  def pipelineFactory(asyncContext: AsyncContext) = (pipeline: ChannelPipeline) ⇒ {
    pipeline.addLast("proxyServerToRemote-idle", new IdleStateHandler(timer, 0, 0, 120))
    pipeline.addLast("proxyServerToRemote-idleAware", new IdleStateAwareChannelHandler {
      override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
        Utils.closeChannel(e.getChannel)
      }
    })
  }
}

class NettyHttpProxyProcessor extends web.ProxyProcessor with NettyTaskSupport with Logging {

  override def pipelineFactory(asyncContext: AsyncContext) = (pipeline: ChannelPipeline) ⇒ {
    super.pipelineFactory(asyncContext)(pipeline)
    pipeline.addLast("proxyServerToRemote-proxyToServerResponseDecoder", new HttpResponseDecoder(DEFAULT_BUFFER_SIZE * 2, DEFAULT_BUFFER_SIZE * 4, DEFAULT_BUFFER_SIZE * 4))
    pipeline.addLast("proxyServerToRemote-proxyToServerHandler", new ProxyHttpResponseRelayingHandler(asyncContext))
  }

  def process(proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse) {
    val task = (asyncContext: AsyncContext) ⇒ createConnection(asyncContext) {
      channel ⇒
        logger.debug(s"[$channel] - Writing proxy request:\n ${Utils.hexDumpToString(proxyRequestBuffer)}")
        channel.write(ChannelBuffers.wrappedBuffer(proxyRequestBuffer))
    }

    starTask(request)(task)
  }
}

class NettyHttpsProxyProcessor extends web.ProxyProcessor with NettyTaskSupport with Logging {
  override def pipelineFactory(asyncContext: AsyncContext) = (pipeline: ChannelPipeline) ⇒ {
    super.pipelineFactory(asyncContext)(pipeline)
    pipeline.addLast("proxyServerToRemote-proxyToServerHandler", new ProxyResponseRelayingHandler(asyncContext))
  }

  def process(proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse) {
    request.getSession(false).getAttribute(SESSION_KEY_ENDPOINT) match {
      case channel: Channel ⇒ {
        if (channel.isConnected) {
          channel.write(ChannelBuffers.wrappedBuffer(proxyRequestBuffer))
        } else {
          //todo: error process
          channel.close()
          writeErrorResponse(response)
        }

      }
      case _ ⇒ {

        val task = (asyncContext: AsyncContext) ⇒ createConnection(asyncContext) {
          channel ⇒
            writeResponse(response, Utils.connectProxyResponse.getBytes("UTF-8"))
        }

        starTask(request)(task)
      }
    }
  }

}

sealed class ProxyHttpResponseRelayingHandler(asyncContext: AsyncContext) extends ProxyResponseRelayingHandler(asyncContext) {

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    val encode = classOf[HttpResponseEncoder].getSuperclass.getDeclaredMethods.filter(_.getName == "encode")(0)
    encode.setAccessible(true)
    val responseBuffer = encode.invoke(new HttpResponseEncoder(), null, ctx.getChannel, e.getMessage).asInstanceOf[ChannelBuffer]
    writeResponse(response, responseBuffer)
    e.getMessage match {
      case response: HttpResponse if !response.isChunked ⇒ Utils.closeChannel(e.getChannel)
      case chunk: HttpChunk if chunk.isLast ⇒ Utils.closeChannel(e.getChannel)
      case _ ⇒
    }
  }
}

sealed class ProxyResponseRelayingHandler(val asyncContext: AsyncContext) extends SimpleChannelUpstreamHandler with Logging {
  val response = asyncContext.getResponse.asInstanceOf[HttpServletResponse]
  val request = asyncContext.getRequest.asInstanceOf[HttpServletRequest]

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.debug(s"[${e.getChannel}] - Receive message:\n ${Utils.formatMessage(e.getMessage)}")
    e.getMessage match {
      case buffer: ChannelBuffer if buffer.readableBytes() > 0 ⇒ writeResponse(response, buffer)
      case _ ⇒
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn(s"[${e.getChannel}] - Got exception", e.getCause)
    e.getChannel.close()
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.warn(s"[${e.getChannel}] - closed, complete request now.")
    Try {
      request.getSession(false).invalidate()
      asyncContext.complete()
    }
  }
}

