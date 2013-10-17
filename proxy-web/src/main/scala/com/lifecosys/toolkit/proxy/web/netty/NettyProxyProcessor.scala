package com.lifecosys.toolkit.proxy.web.netty

import com.lifecosys.toolkit.proxy._
import org.jboss.netty.channel._
import org.jboss.netty.buffer.{ ChannelBuffers, ChannelBuffer }
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import com.typesafe.scalalogging.slf4j.Logging
import com.lifecosys.toolkit.proxy.web._
import javax.servlet.AsyncContext
import scala.util.Try
import org.jboss.netty.handler.codec.http._
import java.nio.channels.ClosedChannelException
import com.lifecosys.toolkit.proxy.ChannelKey
import scala.util.Failure
import scala.Some
import scala.util.Success
import org.jboss.netty.handler.timeout.{ IdleStateEvent, IdleStateAwareChannelHandler, IdleStateHandler }
import scala.collection.mutable.ArrayBuffer
import com.lifecosys.toolkit.proxy.ChannelKey
import scala.util.Failure
import scala.Some
import scala.util.Success
import com.lifecosys.toolkit.proxy.ChannelKey
import scala.util.Failure
import scala.Some
import scala.util.Success
import java.util.{ TimerTask, Timer }
import com.lifecosys.toolkit.proxy.WebProxy.{ WebRequestData, RequestData }

/**
 *
 *
 * @author Young Gu
 * @version 1.0 8/9/13 1:40 PM
 */

sealed trait NettyTaskSupport {

  type Task = (AsyncContext) ⇒ Unit

  //  def addIdleChannelHandler(pipeline: ChannelPipeline) = {
  //    pipeline.addLast("idleHandler", new IdleStateHandler(timer, 0, 0, 120))
  //    pipeline.addLast("idleStateAwareHandler", new IdleStateAwareChannelHandler {
  //      override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) = {
  //        e.getChannel.close()
  //      }
  //    })
  //  }
  //
  //
  def starTask(request: HttpServletRequest)(task: Task) {
    val asyncContext = request.startAsync()
    asyncContext.setTimeout(240 * 1000)
    asyncContext.start(new Runnable {
      def run() {
        task(asyncContext)
      }
    })
  }

  def createConnection(asyncContext: AsyncContext, channelKey: ChannelKey)(connectedCallback: (Channel) ⇒ Unit) = {
    val request = asyncContext.getRequest.asInstanceOf[HttpServletRequest]
    val response = asyncContext.getResponse.asInstanceOf[HttpServletResponse]
    //    request.setAttribute("com.lifecosys.toolkit.proxy.request.completed",false)
    //    logger.error(s">>>>>>>>>>>>>>>>>[${request.getHeader(ProxyRequestID.name)}}] start request... ${parseChannelKey(request)}.. ${parseChannelKey(request).proxyHost.socketAddress}")
    initializeChunkedResponse(response)

    //    new Timer().scheduleAtFixedRate(new TimerTask {
    //      def run() {
    //        logger.error("#################write tick response######################")
    //        writeResponse(request, response, Array[Byte]())
    //      }
    //    }, 10, 10)

    //    logger.error("#################Create connection######################")
    try {
      connect(asyncContext, channelKey) match {
        case Some(channel) ⇒ {
          //          channel.setAttachment(request.getHeader(ProxyRequestID.name))
          //          logger.error(s">>>>>>>>>>>>>>>>>[${channel.getAttachment}] - connection created......${channel}")
          //          synchronized(channels += request.getHeader(ProxyRequestID.name) -> channel)
          //        request.getSession(false).setAttribute(SESSION_KEY_ENDPOINT, channel)
          channel.setAttachment(channelKey.sessionId)
          DefaultHttpsRequestManager.add(channelKey.sessionId, channel)
          connectedCallback(channel)
        }
        case None ⇒ logger.warn(s"[${channelKey.sessionId}] Can't create connection")
      }

    } catch {
      case t: Throwable ⇒ {
        logger.warn(s"[${channelKey.sessionId}]  process request error.....", t)
        writeErrorResponse(response)
        Try(asyncContext.complete())
        logger.info(s"[${channelKey.sessionId}]  request completed.....")
      }
    }

    //    Try(connect(asyncContext, parseChannelKey(request))) match {
    //      case Success(channel) ⇒ {
    //        channel.setAttachment(request.getHeader(ProxyRequestID.name))
    //        logger.error(s">>>>>>>>>>>>>>>>>[${channel.getAttachment}] - connection created......${channel}")
    //        synchronized(channels += request.getHeader(ProxyRequestID.name) -> channel)
    //        //        request.getSession(false).setAttribute(SESSION_KEY_ENDPOINT, channel)
    //        connectedCallback(channel)
    //      }
    //      case Failure(e) ⇒ {
    //
    //        writeErrorResponse(response)
    //        logger.error(s">>>>>>>>>>>>>>>>>[${request.getHeader(ProxyRequestID.name)}}] com.lifecosys.toolkit.proxy.request.completed: ${request.getAttribute("com.lifecosys.toolkit.proxy.request.completed")}")
    //        if(!request.getAttribute("com.lifecosys.toolkit.proxy.request.completed").asInstanceOf[Boolean]){
    //          asyncContext.complete()
    //
    //        }
    //      }
    //    }

  }

  def connect(asyncContext: AsyncContext, channelKey: ChannelKey) = {
    val clientBootstrap = newClientBootstrap
    clientBootstrap.setFactory(clientSocketChannelFactory)
    clientBootstrap setPipelineFactory pipelineFactory(asyncContext)
    val channelFuture = clientBootstrap.connect(channelKey.proxyHost.socketAddress).awaitUninterruptibly()
    if (channelFuture.isSuccess && channelFuture.getChannel.isConnected) {
      channelFuture.getChannel.getConfig.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(DEFAULT_BUFFER_SIZE))
      Some(channelFuture.getChannel)
    } else {
      logger.warn(s"Failure to create connection to ${channelFuture.getChannel}")
      channelFuture.getChannel.close()
      None
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
        Some(channel)
      }
      case _ ⇒ super.connect(asyncContext, channelKey)
    }

  override def pipelineFactory(asyncContext: AsyncContext) = (pipeline: ChannelPipeline) ⇒ {
    super.pipelineFactory(asyncContext)(pipeline)
    //    pipeline.addLast("closedAwareHttpResponseDecoder", new ClosedAwareHttpResponseDecoder(DEFAULT_BUFFER_SIZE * 2, DEFAULT_BUFFER_SIZE * 4, DEFAULT_BUFFER_SIZE * 4))
    //    pipeline.addLast("handler", new HttpProxyResponseRelayingHandler(asyncContext))
    pipeline.addLast("handler", new ProxyResponseRelayingHandler(asyncContext))
  }

  def process(proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse) {

    val requestData = WebRequestData(proxyRequestBuffer)
    val channelKey = ChannelKey(requestData.requestID, requestData.proxyHost)
    val task = (asyncContext: AsyncContext) ⇒ createConnection(asyncContext, channelKey) {
      channel ⇒
        logger.debug(s"[$channel] - Writing proxy request:\n ${Utils.hexDumpToString(proxyRequestBuffer)}")
        channel.write(ChannelBuffers.wrappedBuffer(requestData.request))
    }

    starTask(request)(task)
  }
}

class HttpsNettyProxyProcessor extends web.ProxyProcessor with NettyTaskSupport with Logging {
  override def pipelineFactory(asyncContext: AsyncContext) = (pipeline: ChannelPipeline) ⇒ {
    pipeline.addLast("logger", new NettyLoggingHandler)
    super.pipelineFactory(asyncContext)(pipeline)
    pipeline.addLast("handler", new ProxyResponseRelayingHandler(asyncContext))
  }

  def process(proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse) {

    val requestData = WebRequestData(proxyRequestBuffer)
    val channelKey = ChannelKey(requestData.requestID, requestData.proxyHost)
    DefaultHttpsRequestManager.get(requestData.requestID) match {
      case Some(channel) ⇒ {
        if (channel.isConnected) {
          //          DefaultRequestManager.add(Request(request.getHeader("x-seq"), channel, channel))
          //          logger.error(s">>>>>>>>>>>>>>>>>[${request.getHeader("x-seq")}] --- [${channel}] - process request....")
          channel.write(ChannelBuffers.wrappedBuffer(requestData.request))
        } else {
          //todo: error process
          channel.close()
          writeErrorResponse(response)
        }

      }
      case _ ⇒ {

        val task = (asyncContext: AsyncContext) ⇒ createConnection(asyncContext, channelKey) { channel ⇒

          writeResponse(request, response, Utils.connectProxyResponse.getBytes("UTF-8"))
          //          DefaultRequestManager.add(Request(request.getHeader("x-seq"), channel, channel))
          //          logger.error(s">>>>>>>>>>>>>>>>>[${request.getHeader("x-seq")}] --- [${channel}] - process connection request....")
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
      case buffer: ChannelBuffer if buffer.readableBytes() > 0 ⇒ writeResponse(request, response, buffer)
      case _ ⇒
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.debug(s"[${ctx.getChannel}] - channel closed..")
    Try {
      logger.error(s">>>>>>>>>>>>>>>>>[${e.getChannel.getAttachment.toString}] - request completed...")
      DefaultHttpsRequestManager.remove(e.getChannel.getAttachment.toString)
      Try(asyncContext.complete())
    } match {
      case Success(v) ⇒
      case Failure(t) ⇒ {
        val re = "\n"
        logger.error(s"[${e.getChannel}] - error when closed---\n #######${e.getChannel.getAttachment.toString}############", t)
      }
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn(s"[${e.getChannel}] - Got exception.", e.getCause)
    e.getCause match {
      case closeException: ClosedChannelException ⇒ //Just ignore it
      case exception ⇒ {
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
      case buffer: ChannelBuffer ⇒ writeResponse(request, response, buffer)
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
        DefaultHttpsRequestManager.remove(e.getChannel.getAttachment.toString)
        Try(asyncContext.complete())
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

