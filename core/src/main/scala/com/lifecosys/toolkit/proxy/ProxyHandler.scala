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
import org.jboss.netty.buffer.ChannelBuffer
import com.lifecosys.toolkit.Logger

/**
 *
 *
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 1/1/13 5:54 PM
 */


class ProxyHandler(implicit proxyConfig: ProxyConfig) extends SimpleChannelUpstreamHandler {
  val logger = Logger(getClass)
  val proxyToServerSSLEnable = proxyConfig.proxyToServerSSLEnable


  override def messageReceived(ctx: ChannelHandlerContext, me: MessageEvent) {

    logger.debug("Receive request: %s ".format(me.getMessage))
    me.getMessage match {
      case request: HttpRequest if HttpMethod.CONNECT == request.getMethod => new ConnectionRequestProcessor(request, ctx).process
      case request: HttpRequest => new DefaultRequestProcessor(request, ctx).process
      case _ => throw new UnsupportedOperationException("Unsupported Request..........")
    }
  }

  override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.debug("New channel opened: %s".format(e.getChannel))
    proxyConfig.allChannels.add(e.getChannel)
    super.channelOpen(ctx, e)

  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.debug("Got closed event on : %s".format(e.getChannel))
  }


  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn("Caught exception on proxy -> web connection: " + e.getChannel, e.getCause)
    Utils.closeChannel(e.getChannel)
  }
}


class HttpRelayingHandler(val browserToProxyChannel: Channel)(implicit proxyConfig: ProxyConfig) extends SimpleChannelUpstreamHandler {
  val logger = Logger(getClass)

  private def responsePreProcess(message: Any) = message match {
    case response: HttpResponse if HttpHeaders.Values.CHUNKED == response.getHeader(HttpHeaders.Names.TRANSFER_ENCODING) => {
      val copy = new DefaultHttpResponse(HttpVersion.HTTP_1_1, response.getStatus)
      import scala.collection.JavaConversions._
      response.getHeaderNames.foreach(name => copy.setHeader(name, response.getHeaders(name)))

      copy.setContent(response.getContent)
      copy.setChunked(response.isChunked)
      copy.setHeader(HttpHeaders.Names.TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED)
      copy
    }
    case _ => message
  }


  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.debug("====%s receive message: =======\n %s".format(ctx.getChannel, e.getMessage))

    val message = responsePreProcess(e.getMessage)
    if (browserToProxyChannel.isConnected) {
      browserToProxyChannel.write(message)
    } else {
      if (e.getChannel.isConnected) {
        logger.debug("Closing channel to remote server %s".format(e.getChannel))
        Utils.closeChannel(e.getChannel)
      }
    }

    message match {
      case chunk: HttpChunk if chunk.isLast => Utils.closeChannel(e.getChannel)
      case response: HttpMessage if !response.isChunked => Utils.closeChannel(e.getChannel)
      case _ =>
    }
  }

  override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.debug("New channel opened: %s".format(e.getChannel))
    proxyConfig.allChannels.add(e.getChannel)
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.debug("Got closed event on : %s".format(e.getChannel))
    Utils.closeChannel(browserToProxyChannel)
  }


  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.debug("Caught exception on proxy -> web connection: " + e.getChannel, e.getCause)
    Utils.closeChannel(e.getChannel)
  }
}


class ConnectionRequestHandler(relayChannel: Channel)(implicit proxyConfig: ProxyConfig) extends SimpleChannelUpstreamHandler {
  val logger = Logger(getClass)

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.debug("=====%s receive message:\n %s".format(ctx.getChannel, e.getMessage))
    val msg: ChannelBuffer = e.getMessage.asInstanceOf[ChannelBuffer]
    if (relayChannel.isConnected) {
      relayChannel.write(msg)
    }
  }

  override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    val ch: Channel = e.getChannel
    logger.debug("CONNECT channel opened on: %s".format(ch))
    proxyConfig.allChannels.add(e.getChannel)
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.debug("Got closed event on : %s".format(e.getChannel))
    Utils.closeChannel(relayChannel)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn("Exception on: " + e.getChannel, e.getCause)
    Utils.closeChannel(e.getChannel)
  }
}
