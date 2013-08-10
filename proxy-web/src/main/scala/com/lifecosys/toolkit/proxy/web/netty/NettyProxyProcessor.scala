package com.lifecosys.toolkit.proxy.web.netty

import com.lifecosys.toolkit.proxy._
import org.jboss.netty.channel._
import org.jboss.netty.buffer.{ ChannelBuffers, ChannelBuffer }
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import com.typesafe.scalalogging.slf4j.Logging
import org.jboss.netty.handler.codec.http._
import com.lifecosys.toolkit.proxy.web._
import javax.servlet.AsyncContext
import scala.util.Try

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
    asyncContext.setTimeout(0)
    asyncContext.start(new Runnable {
      def run() {
        task(asyncContext)
      }
    })
  }

  def createConnection(asyncContext: AsyncContext)(connectedCallback: (Channel) ⇒ Unit) = {
    val request = asyncContext.getRequest.asInstanceOf[HttpServletRequest]
    val response = asyncContext.getResponse.asInstanceOf[HttpServletResponse]
    val channelKey = parseChannelKey(request)
    val clientBootstrap = newClientBootstrap
    clientBootstrap.setFactory(clientSocketChannelFactory)
    clientBootstrap.getPipeline.addLast("handler", new ProxyResponseRelayingHandler(asyncContext))
    val channelFuture = clientBootstrap.connect(channelKey.proxyHost.socketAddress).awaitUninterruptibly()
    val channel: Channel = channelFuture.getChannel
    //TODO:Update buffer size.
    //        channel.getConfig.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(DEFAULT_BUFFER_SIZE))
    if (channelFuture.isSuccess() && channel.isConnected) {
      request.getSession(false).setAttribute(SESSION_KEY_ENDPOINT, channel)
      response.setStatus(HttpServletResponse.SC_OK)
      response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
      response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
      response.setHeader(ResponseCompleted.name, "true")
      // Initiate chunked encoding by flushing the headers.
      response.getOutputStream.flush()

      connectedCallback(channel)

    } else {
      //todo: error process
      channelFuture.getChannel.close()
      writeErrorResponse(response)
      asyncContext.complete()
    }

  }

}

class NettyHttpProxyProcessor extends web.ProxyProcessor with NettyTaskSupport with Logging {

  def process(proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse) {
    {
      val task = (asyncContext: AsyncContext) ⇒
        createConnection(asyncContext) { channel ⇒
          logger.debug(s"Writing proxy request to $channel \n ${Utils.hexDumpToString(proxyRequestBuffer)}")
          channel.write(ChannelBuffers.wrappedBuffer(proxyRequestBuffer))
        }

      starTask(request)(task)
    }
  }

}

class NettyHttpsProxyProcessor extends web.ProxyProcessor with NettyTaskSupport with Logging {

  def process(proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse) {
    //    val response=asyncContext.getResponse.asInstanceOf[HttpServletResponse]
    //    val request=asyncContext.getRequest.asInstanceOf[HttpServletRequest]

    request.getSession(false).getAttribute(SESSION_KEY_ENDPOINT) match {
      case channel: Channel ⇒ {
        //        channel.getPipeline.get(classOf[HttpsOutboundHandler]).servletResponse = response
        if (channel.isConnected) {
          channel.write(ChannelBuffers.wrappedBuffer(proxyRequestBuffer))
        } else {
          //todo: error process
          channel.close()
          writeErrorResponse(response)
        }

      }
      case _ ⇒ {

        val task = (asyncContext: AsyncContext) ⇒
          createConnection(asyncContext) { channel ⇒
            logger.debug(s"Writing connection established response")
            response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
            response.getOutputStream.flush()
          }

        starTask(request)(task)
      }
    }
  }

}

sealed class ProxyResponseRelayingHandler(val asyncContext: AsyncContext) extends SimpleChannelUpstreamHandler with Logging {
  val response = asyncContext.getResponse.asInstanceOf[HttpServletResponse]
  val request = asyncContext.getRequest.asInstanceOf[HttpServletRequest]

  //  val finishByte=Array[Byte](0x14, ox03,0x01,0x00 01 01)
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.debug(s"[${e.getChannel}] - Receive message:\n ${Utils.formatMessage(e.getMessage)}")
    e.getMessage match {
      case buffer: ChannelBuffer ⇒ {
        response.getOutputStream.write(buffer.array())
        response.getOutputStream.flush
      }
      case _ ⇒
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn(s"[${e.getChannel}] - Got exception", e.getCause)
    e.getChannel.close()
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.warn(s"[${e.getChannel}] - closed, complete request now.")
    Try(asyncContext.complete())
    request.getSession(false).invalidate()

  }
}

