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
import org.jboss.netty.buffer.{ ChannelBuffers, ChannelBufferInputStream, ChannelBuffer }
import java.nio.channels.ClosedChannelException
import org.apache.http.impl.io.{ DefaultHttpRequestParser, HttpTransportMetricsImpl, SessionInputBufferImpl }
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
  val proxyToServerSSLEnable = proxyConfig.proxyToServerSSLEnable

  override def messageReceived(ctx: ChannelHandlerContext, me: MessageEvent) {

    require(me.getMessage.isInstanceOf[HttpRequest])
    val httpRequest = me.getMessage.asInstanceOf[HttpRequest]
    logger.debug(s"Receive request: $httpRequest")

    val connectHost = proxyConfig.getChainProxyManager.getConnectHost(httpRequest.getUri).get
    if (connectHost.serverType == WebProxyType) {
      if (HttpMethod.CONNECT == httpRequest.getMethod) {
        new WebProxyHttpsRequestProcessor(httpRequest, ctx).process
      } else {
        new WebProxyHttpRequestProcessor(httpRequest, ctx).process
      }
    } else {
      me.getMessage match {
        case request: HttpRequest if HttpMethod.CONNECT == request.getMethod ⇒ new NetHttpsRequestProcessor(request, ctx).process
        case request: HttpRequest ⇒ new DefaultHttpRequestProcessor(request, ctx).process
        case _ ⇒ throw new UnsupportedOperationException("Unsupported Request..........")
      }
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
    logger.warn("Caught exception on : %s".format(e.getChannel), e.getCause)
    e.getCause match {
      case closeException: ClosedChannelException ⇒ //Just ignore it
      case exception                              ⇒ Utils.closeChannel(e.getChannel)
    }
  }
}

class HttpRelayingHandler(browserChannel: Channel)(implicit proxyConfig: ProxyConfig)
    extends SimpleChannelUpstreamHandler with Logging {

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

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.debug("====%s receive message: =======\n %s".format(ctx.getChannel, e.getMessage))

    val message = responsePreProcess(e.getMessage)
    if (browserChannel.isConnected) {
      browserChannel.write(message) match {
        case future: ChannelFuture ⇒ future.addListener {
          future: ChannelFuture ⇒
            message match {
              case chunk: HttpChunk if chunk.isLast ⇒ Utils.closeChannel(e.getChannel)
              case response: HttpMessage if !response.isChunked ⇒ Utils.closeChannel(e.getChannel)
              case _ ⇒
            }
        }
      }
    } else {
      logger.debug("Closing channel to remote server %s".format(e.getChannel))
      Utils.closeChannel(e.getChannel)
    }

  }

  override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.debug("New channel opened: %s".format(e.getChannel))
    proxyConfig.allChannels.add(e.getChannel)
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

class WebProxyHttpRelayingHandler(browserChannel: Channel)(implicit proxyConfig: ProxyConfig)
    extends SimpleChannelUpstreamHandler with Logging {
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.error(s"################################################################################")
    logger.error(Utils.formatMessage(e.getMessage))
    def writeResponse(msg: Any) = if (browserChannel.isConnected) {
      browserChannel.write(msg).addListener {
        future: ChannelFuture ⇒
          logger.debug("Write response to browser completed.")
          e.getMessage match {
            case response: HttpResponse if response.getHeader("response-completed").toBoolean ⇒ Utils.closeChannel(browserChannel)
          }
      }
    }

    e.getMessage match {
      case response: HttpResponse if !response.isChunked ⇒ {
        if (response.getContent.readableBytes() == Utils.connectProxyResponse.getBytes(Utils.UTF8).length &&
          Utils.connectProxyResponse == IOUtils.toString(new ChannelBufferInputStream(ChannelBuffers.copiedBuffer(response.getContent)))) {
          import scala.collection.JavaConverters._
          val setCookie = response.getHeader(HttpHeaders.Names.SET_COOKIE)
          val jsessionid = new CookieDecoder().decode(setCookie).asScala.filter(_.getName == "JSESSIONID").headOption
          browserChannel.setAttachment(jsessionid)
        }

        writeResponse(response.getContent)
      }
      case response: HttpResponse if response.isChunked ⇒
      case response: HttpChunk if !response.isLast ⇒ writeResponse(response.getContent)
      case response: HttpChunk if response.isLast ⇒
      case e ⇒ writeResponse(e)
    }

  }
}

class ConnectionRequestHandler(browserChannel: Channel)(implicit proxyConfig: ProxyConfig)
    extends SimpleChannelUpstreamHandler with Logging {
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.error(s"#################################${ctx.getChannel}###############################################")
    logger.error(s"=====${ctx.getChannel} receive message: \n ${e.getMessage}")
    e.getMessage match {

      case response: HttpResponse                  ⇒ logger.error(s"Length: ${response.getContent.readableBytes()}\n${Utils.formatBuffer(response.getContent)}")
      case response: HttpChunk if !response.isLast ⇒ logger.error(s"Chunk, Length: ${response.getContent.readableBytes()}\n${Utils.formatBuffer(response.getContent)}")
      case response: HttpChunk if response.isLast  ⇒ logger.error(s"Last chunk, Length: ${response.getContent.readableBytes()}\n${Utils.formatBuffer(response.getContent)}")
      case response: ChannelBuffer                 ⇒ logger.error(s"Length: ${response.readableBytes()}\n${Utils.formatBuffer(response)}")
      case e                                       ⇒ logger.error(s"=====${ctx.getChannel} receive unknown message: \n ${e}")
    }
    logger.error(s"################################################################################")

    //    try {
    //      val buffer = new SessionInputBufferImpl(new HttpTransportMetricsImpl(), 1024, 2048, null, null)
    //      buffer.bind(new ChannelBufferInputStream(ChannelBuffers.copiedBuffer(e.getMessage.asInstanceOf[ChannelBuffer])))
    //      val parser = new DefaultHttpRequestParser(buffer)
    //      val proxyRequest = parser.parse()
    //
    //      println("#######################" + proxyRequest)
    //    }
    //    catch {
    //      case e => println("#######################")
    //    }
    //

    def writeResponse(msg: Any) {
      if (browserChannel.isConnected) {
        browserChannel.write(msg)
      }
    }
    e.getMessage match {
      case response: HttpResponse if !response.isChunked ⇒ {
        if (response.getContent.readableBytes() == Utils.connectProxyResponse.getBytes(Utils.UTF8).length &&
          Utils.connectProxyResponse == IOUtils.toString(new ChannelBufferInputStream(ChannelBuffers.copiedBuffer(response.getContent)))) {
          import scala.collection.JavaConverters._
          val setCookie = response.getHeader(HttpHeaders.Names.SET_COOKIE)
          val jsessionid = new CookieDecoder().decode(setCookie).asScala.filter(_.getName == "JSESSIONID").headOption
          browserChannel.setAttachment(jsessionid)
        }

        writeResponse(response.getContent)
      }
      case response: HttpResponse if response.isChunked ⇒
      case response: HttpChunk if !response.isLast ⇒ writeResponse(response.getContent)
      case response: HttpChunk if response.isLast ⇒
      case e ⇒ writeResponse(e)
    }

  }

  override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    val ch: Channel = e.getChannel
    logger.debug("CONNECT channel opened on: %s".format(ch))
    proxyConfig.allChannels.add(e.getChannel)
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.error("Got closed event on : %s".format(e.getChannel))
    //    Utils.closeChannel(browserChannel)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn("Caught exception on : %s".format(e.getChannel), e.getCause)
    e.getCause match {
      case closeException: ClosedChannelException ⇒ //Just ignore it
      case exception                              ⇒ Utils.closeChannel(e.getChannel)
    }
  }
}

