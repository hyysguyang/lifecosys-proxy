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
import org.jboss.netty.buffer.{ ChannelBuffers, ChannelBufferInputStream }
import java.nio.channels.ClosedChannelException
import com.typesafe.scalalogging.slf4j.Logging
import org.apache.commons.io.IOUtils

/**
 *
 *
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 1/1/13 5:54 PM
 */

class ProxyHandler(implicit proxyConfig: ProxyConfig)
    extends SimpleChannelUpstreamHandler with Logging {

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.debug(s"${e.getChannel} receive message\n${Utils.formatMessage(e.getMessage)}")

    require(e.getMessage.isInstanceOf[HttpRequest], "Unsupported Request..........")
    val httpRequest = e.getMessage.asInstanceOf[HttpRequest]

    implicit val connectHost = proxyConfig.getChainProxyManager.getConnectHost(httpRequest.getUri).get

    val requestProcessor = connectHost.serverType match {
      case WebProxyType if HttpMethod.CONNECT == httpRequest.getMethod ⇒ new WebProxyHttpsRequestProcessor(httpRequest, ctx)
      case WebProxyType ⇒ new WebProxyHttpRequestProcessor(httpRequest, ctx)
      case other if HttpMethod.CONNECT == httpRequest.getMethod ⇒ new NetHttpsRequestProcessor(httpRequest, ctx)
      case other ⇒ new DefaultHttpRequestProcessor(httpRequest, ctx)
    }

    requestProcessor process

  }

  override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.debug("New channel opened: %s".format(e.getChannel))
    //    proxyConfig.allChannels.add(e.getChannel)
    super.channelOpen(ctx, e)

  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.debug("Got closed event on : %s".format(e.getChannel))
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn("Caught exception on : %s".format(e.getChannel), e.getCause)
    e.getCause match {
      case closeException: ClosedChannelException ⇒ //Just ignore it
      case exception                              ⇒ Utils.closeChannel(e.getChannel)
    }
  }
}

class NetHttpResponseRelayingHandler(browserChannel: Channel)(implicit proxyConfig: ProxyConfig)
    extends BaseRelayingHandler(browserChannel) with Logging {

  private def responsePreProcess(message: Any) = message match {
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

  override def processMessage(ctx: ChannelHandlerContext, e: MessageEvent) {

    val message = responsePreProcess(e.getMessage)
    def writeListener = (future: ChannelFuture) ⇒ {
      message match {
        case chunk: HttpChunk if chunk.isLast ⇒ Utils.closeChannel(e.getChannel)
        case response: HttpMessage if !response.isChunked ⇒ Utils.closeChannel(e.getChannel)
        case _ ⇒
      }
    }

    writeResponse(writeListener)(message)
    if (!browserChannel.isConnected) {
      logger.debug(s"Closing channel ${e.getChannel}")
      Utils.closeChannel(e.getChannel)
    }

  }

  override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.debug("New channel opened: %s".format(e.getChannel))
    //    proxyConfig.allChannels.add(e.getChannel)
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.debug("Got closed event on : %s".format(e.getChannel))
    Utils.closeChannel(browserChannel)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn("Caught exception on : %s".format(e.getChannel), e.getCause)
    e.getCause match {
      case closeException: ClosedChannelException ⇒ //Just ignore it
      case exception                              ⇒ Utils.closeChannel(e.getChannel)
    }
  }
}

abstract class BaseRelayingHandler(relayingChannel: Channel)(implicit proxyConfig: ProxyConfig)
    extends SimpleChannelUpstreamHandler with Logging {

  def writeResponse(writeListener: ChannelFutureListener)(msg: Any) =
    if (relayingChannel.isConnected) relayingChannel.write(msg) addListener writeListener

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.debug(s"############${e.getChannel} receive message###############\n ${Utils.formatMessage(e.getMessage)}")
    processMessage(ctx, e)
  }

  def processMessage(ctx: ChannelHandlerContext, e: MessageEvent)

  override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    val ch: Channel = e.getChannel
    logger.debug("CONNECT channel opened on: %s".format(ch))
    //    proxyConfig.allChannels.add(e.getChannel)
  }
}

class NetHttpsRelayingHandler(relayingChannel: Channel)(implicit proxyConfig: ProxyConfig)
    extends BaseRelayingHandler(relayingChannel) with Logging {

  override def processMessage(ctx: ChannelHandlerContext, e: MessageEvent) {
    def writeListener = (future: ChannelFuture) ⇒ logger.debug(s"Write data to channel ${relayingChannel} completed.")
    writeResponse(writeListener)(e.getMessage)

    if (!relayingChannel.isConnected) {
      logger.debug(s"Closing channel ${e.getChannel}")
      Utils.closeChannel(e.getChannel)
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.warn("Got closed event on : %s".format(e.getChannel))
    Utils.closeChannel(relayingChannel)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn("Caught exception on : %s".format(e.getChannel), e.getCause)
    e.getCause match {
      case closeException: ClosedChannelException ⇒ //Just ignore it
      case exception                              ⇒ Utils.closeChannel(e.getChannel)
    }
  }
}

trait WebProxyRelayingHandler {

  def processMessage(browserChannel: Channel)(message: Any)(write: (Any) ⇒ Unit) {
    message match {
      case response: HttpResponse if response.getStatus.getCode != 200 ⇒ {
        throw new RuntimeException("WebProx Error:")
      }
      case response: HttpResponse if !response.isChunked ⇒ {
        if (response.getContent.readableBytes() == Utils.connectProxyResponse.getBytes(Utils.UTF8).length &&
          Utils.connectProxyResponse == IOUtils.toString(new ChannelBufferInputStream(ChannelBuffers.copiedBuffer(response.getContent)))) {
          import scala.collection.JavaConverters._
          val setCookie = response.getHeader(HttpHeaders.Names.SET_COOKIE)
          val jsessionid = new CookieDecoder().decode(setCookie).asScala.filter(_.getName == "JSESSIONID").headOption
          browserChannel.setAttachment(jsessionid)
          //          browserChannel.setAttachment(HttpsState(jsessionid, ClientHello))
        }

        write(response.getContent)
      }
      case response: HttpResponse if response.isChunked ⇒
      case response: HttpChunk if !response.isLast      ⇒ write(response.getContent)
      case response: HttpChunk if response.isLast       ⇒
      case unknownMessage                               ⇒ write(unknownMessage)
    }
  }
}
class WebProxyHttpRelayingHandler(browserChannel: Channel)(implicit proxyConfig: ProxyConfig)
    extends BaseRelayingHandler(browserChannel) with WebProxyRelayingHandler with Logging {

  override def processMessage(ctx: ChannelHandlerContext, e: MessageEvent) {

    def writeListener = (future: ChannelFuture) ⇒ {
      logger.debug(s"Write data to browser channel ${browserChannel} completed.")
      e.getMessage match { //Note:"response-completed" header should not be null, so we ignore to check it.
        case response: HttpResponse if response.getHeader("response-completed").toBoolean ⇒ Utils.closeChannel(browserChannel)
        case _ ⇒ //Just ignore it.
      }
    }
    processMessage(browserChannel)(e.getMessage)(writeResponse(writeListener) _)
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.debug("Got closed event on : %s".format(e.getChannel))
    //    channels.foreach(Utils.closeChannel(_))
    //    synchronized(channels.clear())
    Utils.closeChannel(browserChannel)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn("Caught exception on : %s".format(e.getChannel), e.getCause)
    e.getCause match {
      case closeException: ClosedChannelException ⇒ //Just ignore it
      case exception ⇒ {
        Utils.closeChannel(e.getChannel)
        Utils.closeChannel(browserChannel)
      }
    }
  }
}

class WebProxyHttpsRelayingHandler(browserChannel: Channel)(implicit proxyConfig: ProxyConfig)
    extends BaseRelayingHandler(browserChannel) with WebProxyRelayingHandler with Logging {

  override def processMessage(ctx: ChannelHandlerContext, e: MessageEvent) {
    def writeListener = (future: ChannelFuture) ⇒ {
      e.getMessage match {
        case response: HttpResponse if !response.isChunked && response.getHeader("response-completed").toBoolean ⇒ {
          //          Utils.channelFutures += Channels.succeededFuture(e.getChannel)
          //          Utils.closeChannel(browserChannel)
        }
      }
      logger.debug(s"Write data to browser channel ${browserChannel} completed.")
    }
    processMessage(browserChannel)(e.getMessage)(writeResponse(writeListener) _)
  }

  override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    val ch: Channel = e.getChannel
    logger.debug("Open channel to remote webproxy: %s".format(ch))
    //    proxyConfig.allChannels.add(e.getChannel)
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.warn("Got closed event on : %s".format(e.getChannel))
    //    Utils.closeChannel(browserChannel)
  }
  //
  //  override def channelConnected(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
  //    val sslHandler: SslHandler = ctx.getPipeline.get(classOf[SslHandler])
  //    // Begin handshake.
  //    sslHandler.handshake
  //  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn("Caught exception on : %s".format(e.getChannel), e.getCause)
    e.getCause match {
      case closeException: ClosedChannelException ⇒ //Just ignore it
      case exception ⇒ {
        Utils.closeChannel(e.getChannel)
        Utils.closeChannel(browserChannel)
      }
    }
  }
}

