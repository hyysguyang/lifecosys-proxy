package com.lifecosys.toolkit.proxy.web.netty

import com.lifecosys.toolkit.proxy._
import org.jboss.netty.channel._
import org.jboss.netty.buffer.{ ChannelBuffers, ChannelBuffer }
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import com.typesafe.scalalogging.slf4j.Logging
import org.jboss.netty.handler.codec.http._
import com.lifecosys.toolkit.proxy.web._
import javax.servlet.AsyncContext
import com.lifecosys.toolkit.proxy.ChannelKey
import scala.util.Try

/**
 *
 *
 * @author Young Gu
 * @version 1.0 8/9/13 1:40 PM
 */

class NettyRequestProcessor extends Logging {

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

  def createConnection(asyncContext: AsyncContext, channelKey: ChannelKey)(connectedCallback: (Channel) ⇒ Unit) = {
    val request = asyncContext.getRequest.asInstanceOf[HttpServletRequest]
    val response = asyncContext.getResponse.asInstanceOf[HttpServletResponse]
    val clientBootstrap = newClientBootstrap
    clientBootstrap.setFactory(clientSocketChannelFactory)
    clientBootstrap.getPipeline.addLast("handler", new ProxyResponseRelayingHandler(asyncContext))
    val channelFuture = clientBootstrap.connect(channelKey.proxyHost.socketAddress).awaitUninterruptibly()
    val channel: Channel = channelFuture.getChannel
    //TODO:Update buffer size.
    //        channel.getConfig.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(DEFAULT_BUFFER_SIZE))
    if (channelFuture.isSuccess() && channel.isConnected) {
      request.getSession(false).setAttribute("channel", channel)
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

class NettyHttpRequestProcessor extends NettyRequestProcessor {

  def releaseTask(channelKey: ChannelKey) {}

  def process(channelKey: ChannelKey, proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse) {
    {
      //    val response=asyncContext.getResponse.asInstanceOf[HttpServletResponse]
      //    val request=asyncContext.getRequest.asInstanceOf[HttpServletRequest]

      request.getSession(false).getAttribute("channel") match {
        case channel: Channel ⇒ {
          //        channel.getPipeline.get(classOf[HttpsOutboundHandler]).servletResponse = response
          if (channel.isConnected) {
            //          def completeRequestIfNecessary(buffer:ChannelBuffer) {
            //            val isCloseRecord = buffer.readableBytes() > 5 && buffer.getByte(0) == 0x15 &&
            //              (buffer.getShort(3) + 5) == buffer.readableBytes()
            //
            //            if (isCloseRecord) {
            //             Try(channel.getPipeline.get(classOf[ProxyResponseRelayingHandler]).asyncContext.complete())
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
        case _ ⇒ {

          val task = (asyncContext: AsyncContext) ⇒ {
            createConnection(asyncContext, channelKey) { channel ⇒
              logger.debug(s"Writing proxy request to $channel \n ${Utils.hexDumpToString(proxyRequestBuffer)}")
              channel.write(ChannelBuffers.wrappedBuffer(proxyRequestBuffer))
            }
          }
          starTask(request)(task)
        }
      }

    }
  }

}

class NettyHttpsRequestProcessor extends NettyRequestProcessor {

  def releaseTask(channelKey: ChannelKey) {}

  def process(channelKey: ChannelKey, proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse) {
    //    val response=asyncContext.getResponse.asInstanceOf[HttpServletResponse]
    //    val request=asyncContext.getRequest.asInstanceOf[HttpServletRequest]

    request.getSession(false).getAttribute("channel") match {
      case channel: Channel ⇒ {
        //        channel.getPipeline.get(classOf[HttpsOutboundHandler]).servletResponse = response
        if (channel.isConnected) {
          //          def completeRequestIfNecessary(buffer:ChannelBuffer) {
          //            val isCloseRecord = buffer.readableBytes() > 5 && buffer.getByte(0) == 0x15 &&
          //              (buffer.getShort(3) + 5) == buffer.readableBytes()
          //
          //            if (isCloseRecord) {
          //             Try(channel.getPipeline.get(classOf[ProxyResponseRelayingHandler]).asyncContext.complete())
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
      case _ ⇒ {

        val task = (asyncContext: AsyncContext) ⇒ {
          createConnection(asyncContext, channelKey) { channel ⇒
            logger.debug(s"Writing connection established response")
            response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
            response.getOutputStream.flush()
          }
        }
        starTask(request)(task)
      }
    }
  }

}

class ProxyResponseRelayingHandler(val asyncContext: AsyncContext) extends SimpleChannelUpstreamHandler with Logging {
  val response = asyncContext.getResponse.asInstanceOf[HttpServletResponse]
  val request = asyncContext.getRequest.asInstanceOf[HttpServletRequest]

  //  val finishByte=Array[Byte](0x14, ox03,0x01,0x00 01 01)
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.debug(s"[${request.getSession(false).getId}] - Receive message:\n ${Utils.formatMessage(e.getMessage)}")
    e.getMessage match {
      case buffer: ChannelBuffer ⇒ {
        response.getOutputStream.write(buffer.array())
        response.getOutputStream.flush
      }
      case _ ⇒
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn(s"Got exception on ${ctx.getChannel}", e.getCause)
    e.getChannel.close()
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.warn(s"Got closed event on ${e.getChannel}, complete request now and remove channel from ChannelManager.")
    request.getSession(false).removeAttribute("channel")
    Try(asyncContext.complete())
    request.getSession(false).invalidate()

  }
}

