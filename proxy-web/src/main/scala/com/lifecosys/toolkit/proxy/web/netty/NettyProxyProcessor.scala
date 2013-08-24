package com.lifecosys.toolkit.proxy.web.netty

import com.lifecosys.toolkit.proxy._
import org.jboss.netty.channel._
import org.jboss.netty.buffer.{ ChannelBuffers, ChannelBuffer }
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import com.typesafe.scalalogging.slf4j.Logging
import com.lifecosys.toolkit.proxy.web._
import javax.servlet.AsyncContext
import scala.util.{ Failure, Success, Try }
import org.jboss.netty.handler.codec.http._
import com.lifecosys.toolkit.proxy.ChannelKey
import java.nio.channels.ClosedChannelException

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
    Try(connect(asyncContext, parseChannelKey(request))) match {
      case Success(channel) ⇒ {
        request.getSession(false).setAttribute(SESSION_KEY_ENDPOINT, channel)
        initializeChunkedResponse(response)
        connectedCallback(channel)
      }
      case Failure(e) ⇒ {
        logger.warn("Can't create connection", e)
        writeErrorResponse(response)
        asyncContext.complete()
      }
    }

  }

  def connect(asyncContext: AsyncContext, channelKey: ChannelKey) = {
    val clientBootstrap = newClientBootstrap
    clientBootstrap.setFactory(clientSocketChannelFactory)
    clientBootstrap setPipelineFactory pipelineFactory(asyncContext)
    val channelFuture = clientBootstrap.connect(channelKey.proxyHost.socketAddress).awaitUninterruptibly()
    if (channelFuture.isSuccess && channelFuture.getChannel.isConnected) {
      channelFuture.getChannel.getConfig.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(DEFAULT_BUFFER_SIZE))
      channelFuture.getChannel
    } else {
      channelFuture.getChannel.close()
      throw new RuntimeException(s"Failure to create connection to ${channelFuture.getChannel}")
    }
  }

  def pipelineFactory(asyncContext: AsyncContext) = (pipeline: ChannelPipeline) ⇒ addIdleChannelHandler(pipeline)
}

class HttpNettyProxyProcessor extends web.ProxyProcessor with NettyTaskSupport with Logging {

  override def connect(asyncContext: AsyncContext, channelKey: ChannelKey) =
    HttpChannelManager.get(channelKey.proxyHost.socketAddress) match {
      case Some(channelFuture) if channelFuture.getChannel.isConnected ⇒ {
        val channel = channelFuture.getChannel
        logger.debug(s"$HttpChannelManager")
        logger.info(s"Use existed channel ${channel}")
        channel.getPipeline.replace(classOf[HttpProxyResponseRelayingHandler], "handler", new HttpProxyResponseRelayingHandler(asyncContext))
        channel
      }
      case _ ⇒ super.connect(asyncContext, channelKey)
    }

  override def pipelineFactory(asyncContext: AsyncContext) = (pipeline: ChannelPipeline) ⇒ {
    super.pipelineFactory(asyncContext)(pipeline)
    pipeline.addLast("closedAwareHttpResponseDecoder", new ClosedAwareHttpResponseDecoder(DEFAULT_BUFFER_SIZE * 2, DEFAULT_BUFFER_SIZE * 4, DEFAULT_BUFFER_SIZE * 4))
    pipeline.addLast("handler", new HttpProxyResponseRelayingHandler(asyncContext))
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

class HttpsNettyProxyProcessor extends web.ProxyProcessor with NettyTaskSupport with Logging {
  override def pipelineFactory(asyncContext: AsyncContext) = (pipeline: ChannelPipeline) ⇒ {
    super.pipelineFactory(asyncContext)(pipeline)
    pipeline.addLast("handler", new ProxyResponseRelayingHandler(asyncContext))
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

        val task = (asyncContext: AsyncContext) ⇒ createConnection(asyncContext) { channel ⇒
          writeResponse(response, Utils.connectProxyResponse.getBytes("UTF-8"))
        }
        starTask(request)(task)
      }
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

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.info(s"[${e.getChannel}] - closed, complete request now.")
    Try {
      request.getSession(false).invalidate()
      asyncContext.complete()
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    e.getCause match {
      case closeException: ClosedChannelException ⇒ //Just ignore it
      case exception ⇒ {
        logger.warn(s"[${e.getChannel}] - Got exception.", e.getCause)
        Utils.closeChannel(e.getChannel)
      }
    }
  }

}

class ClosedAwareHttpResponseDecoder(maxInitialLineLength: Int, maxHeaderSize: Int, maxChunkSize: Int)
    extends HttpResponseDecoder(maxInitialLineLength, maxHeaderSize, maxChunkSize) {

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {

    e.getMessage match {
      case buffer: ChannelBuffer ⇒
        //We decode the response to determine if we need close the connection after send the buffer
        val event = new UpstreamMessageEvent(ctx.getChannel, ChannelBuffers.copiedBuffer(buffer), e.getRemoteAddress)
        ctx.sendUpstream(e)
        super.messageReceived(ctx, event)
      case _ ⇒ super.messageReceived(ctx, e)
    }
  }

}

sealed class HttpProxyResponseRelayingHandler(asyncContext: AsyncContext) extends ProxyResponseRelayingHandler(asyncContext) {

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.debug(s"[${ctx.getChannel}] - Receive message\n ${e.getMessage}")

    e.getMessage match {
      case buffer: ChannelBuffer ⇒ writeResponse(response, buffer)
      case response: HttpResponse if !response.isChunked ⇒
        if (HttpHeaders.isKeepAlive(response))
          reuseChannel
        else
          Utils.closeChannel(e.getChannel)
      case chunk: HttpChunk if chunk.isLast ⇒ Utils.closeChannel(e.getChannel)
      case unknown                          ⇒ //Just ignore it for other message.
    }

    def reuseChannel {
      Try {
        request.getSession(false).invalidate()
        asyncContext.complete()
      }

      HttpChannelManager.add(e.getChannel.getRemoteAddress, Channels.succeededFuture(e.getChannel))
      logger.debug(s"$HttpChannelManager")
      logger.info(s"[${e.getChannel}] - Success to reuse channel.")
    }

  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    super.channelClosed(ctx, e)
    HttpChannelManager.removeClosedChannel(e.getChannel.getRemoteAddress)
  }
}

