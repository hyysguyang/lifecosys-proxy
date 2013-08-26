/*
 * ===Begin Copyright Notice===
 *
 *  NOTICE
 *
 *  THIS SOFTWARE IS THE PROPERTY OF AND CONTAINS CONFIDENTIAL INFORMATION OF
 *  LIFECOSYS AND/OR ITS AFFILIATES OR SUBSIDIARIES AND SHALL NOT BE DISCLOSED
 *  WITHOUT PRIOR WRITTEN PERMISSION. LICENSED CUSTOMERS MAY COPY AND ADAPT
 *  THIS SOFTWARE FOR THEIR OWN USE IN ACCORDANCE WITH THE TERMS OF THEIR
 *  SOFTWARE LICENSE AGREEMENT. ALL OTHER RIGHTS RESERVED.
 *
 *  (c) COPYRIGHT 2013 LIFECOCYS. ALL RIGHTS RESERVED. THE WORD AND DESIGN
 *  MARKS SET FORTH HEREIN ARE TRADEMARKS AND/OR REGISTERED TRADEMARKS OF
 *  LIFECOSYS AND/OR ITS AFFILIATES AND SUBSIDIARIES. ALL RIGHTS RESERVED.
 *  ALL LIFECOSYS TRADEMARKS LISTED HEREIN ARE THE PROPERTY OF THEIR RESPECTIVE
 *  OWNERS.
 *
 *  ===End Copyright Notice===
 */

package com.lifecosys.toolkit.proxy

import org.jboss.netty.channel._
import org.jboss.netty.handler.codec.http._
import java.nio.channels.ClosedChannelException
import com.typesafe.scalalogging.slf4j.Logging
import org.jboss.netty.buffer.ChannelBuffer

/**
 *
 *
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 1/1/13 5:54 PM
 */

abstract class BaseRelayingHandler(relayingChannel: Channel)(implicit proxyConfig: ProxyConfig)
    extends SimpleChannelUpstreamHandler with Logging {

  val defaultWriteListener = (future: ChannelFuture) ⇒ {
    logger.debug(s"[${future.getChannel}] - Write data to channel $relayingChannel completed.")
  }

  def writeResponse(msg: Any, writeListener: ChannelFutureListener = defaultWriteListener) =
    if (relayingChannel.isConnected) relayingChannel.write(msg) addListener writeListener

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.debug(s"${e.getChannel} Receive message\n ${Utils.formatMessage(e.getMessage)}")
    processMessage(ctx, e)
  }

  def processMessage(ctx: ChannelHandlerContext, e: MessageEvent)

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.info(s"[${e.getChannel}] - closed.")
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

trait HttpResponseRelayingHandler {
  def responsePreProcess(message: Any) = message match {
    case response: HttpResponse if HttpHeaders.Values.CHUNKED == response.getHeader(HttpHeaders.Names.TRANSFER_ENCODING) ⇒ {
      //Fixing HTTP version.
      val copy = new DefaultHttpResponse(HttpVersion.HTTP_1_1, response.getStatus)
      import scala.collection.JavaConversions._
      response.getHeaderNames.foreach(name ⇒ copy.setHeader(name, response.getHeaders(name)))

      copy.setContent(response.getContent)
      copy.setChunked(response.isChunked)
      copy.setHeader(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED)
      copy
    }
    case _ ⇒ message
  }
}

class NetHttpResponseRelayingHandler(browserChannel: Channel)(implicit proxyConfig: ProxyConfig)
    extends BaseRelayingHandler(browserChannel) with HttpResponseRelayingHandler {

  override def processMessage(ctx: ChannelHandlerContext, e: MessageEvent) {

    val message = responsePreProcess(e.getMessage)
    writeResponse(message, writeListener)

    def writeListener = (future: ChannelFuture) ⇒ {
      message match {
        case response: HttpMessage if !response.isChunked ⇒ {
          if (!HttpHeaders.isKeepAlive(response))
            Utils.closeChannel(e.getChannel)
          else {
            HttpChannelManager.add(e.getChannel.getRemoteAddress, Channels.succeededFuture(e.getChannel))
            logger.debug(s"$HttpChannelManager")
            logger.info(s"[${e.getChannel}] - Success to reuse channel.")
          }
        }
        case chunk: HttpChunk if chunk.isLast ⇒ Utils.closeChannel(e.getChannel)
        case _                                ⇒
      }
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    super.channelClosed(ctx, e)
    HttpChannelManager.removeClosedChannel(e.getChannel.getRemoteAddress)
    Utils.closeChannel(browserChannel)
  }
}

class NetHttpsRelayingHandler(relayingChannel: Channel)(implicit proxyConfig: ProxyConfig)
    extends BaseRelayingHandler(relayingChannel) {

  override def processMessage(ctx: ChannelHandlerContext, e: MessageEvent) {
    writeResponse(e.getMessage)

    if (!relayingChannel.isConnected) {
      Utils.closeChannel(e.getChannel)
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    super.channelClosed(ctx, e)
    Utils.closeChannel(relayingChannel)
  }
}

class WebProxyHttpRelayingHandler(browserChannel: Channel)(implicit proxyConfig: ProxyConfig)
    extends BaseRelayingHandler(browserChannel) with HttpResponseRelayingHandler {

  override def processMessage(ctx: ChannelHandlerContext, e: MessageEvent) {

    val message = responsePreProcess(e.getMessage)
    message match {
      case WebProxy.Close ⇒ {
        HttpChannelManager.add(e.getChannel.getRemoteAddress, Channels.succeededFuture(e.getChannel))
        logger.debug(s"$HttpChannelManager")
        logger.info(s"[${e.getChannel}] - Success to reuse channel.")
      }
      case _ ⇒ writeResponse(message)
    }
  }

  //DO NOT CLOSE browser channel
  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    super.channelClosed(ctx, e)
    HttpChannelManager.removeClosedChannel(e.getChannel.getRemoteAddress)
  }
}

class WebProxyHttpsRelayingHandler(browserChannel: Channel)(implicit proxyConfig: ProxyConfig)
    extends BaseRelayingHandler(browserChannel) {

  override def processMessage(ctx: ChannelHandlerContext, e: MessageEvent) {
    //    if (browserChannel.getPipeline.get(classOf[HttpResponseEncoder]) != null) {
    //      logger.error("^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^")
    //      browserChannel.getPipeline remove classOf[HttpResponseEncoder]
    //    }

    e.getMessage match {
      case responseBuffer: ChannelBuffer if responseBuffer.readableBytes() > 0 ⇒ writeResponse(responseBuffer)
      case WebProxy.Close ⇒ {
        HttpsChannelManager.add(e.getChannel.getRemoteAddress, Channels.succeededFuture(e.getChannel))
        logger.debug(s"$HttpsChannelManager")
        logger.info(s"[${e.getChannel}] - Success to reuse channel.")
      }
      case _ ⇒
    }
  }

  //DO NOT CLOSE browser channel
  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    super.channelClosed(ctx, e)
    HttpsChannelManager.removeClosedChannel(e.getChannel.getRemoteAddress)
  }
}

