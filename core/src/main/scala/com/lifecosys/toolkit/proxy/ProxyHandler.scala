package com.lifecosys.toolkit.proxy

import org.jboss.netty.channel._
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.buffer.ChannelBuffer
import com.lifecosys.toolkit.proxy.ProxyServer._

/**
 * 
 *
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 1/1/13 5:54 PM
 */



class ProxyHandler(implicit proxyConfig: ProxyConfig) extends SimpleChannelUpstreamHandler {

  val proxyToServerSSLEnable = proxyConfig.proxyToServerSSLEnable


  override def messageReceived(ctx: ChannelHandlerContext, me: MessageEvent) {

    if (logger.isDebugEnabled()) logger.debug("Receive request: {} ", me.getMessage)
    me.getMessage match {
      case request: HttpRequest if HttpMethod.CONNECT == request.getMethod => new ConnectionRequestProcessor(request, ctx).process
      case request: HttpRequest => new DefaultRequestProcessor(request, ctx).process
      case _ => throw new UnsupportedOperationException("Unsupported Request..........")
    }
  }

  override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    if (logger.isDebugEnabled()) logger.debug("New channel opened: {}", e.getChannel)
    proxyConfig.allChannels.add(e.getChannel)
    super.channelOpen(ctx, e)

  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    if (logger.isDebugEnabled()) logger.debug("Got closed event on : {}", e.getChannel)
  }


  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn("Caught exception on proxy -> web connection: " + e.getChannel, e.getCause)
    Utils.closeChannel(e.getChannel)
  }
}



class HttpRelayingHandler(val browserToProxyChannel: Channel)(implicit proxyConfig: ProxyConfig) extends SimpleChannelUpstreamHandler {

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
    if (logger.isDebugEnabled()) logger.debug("========{} receive message: =======\n {}", ctx.getChannel.asInstanceOf[Any], e.getMessage)

    val message = responsePreProcess(e.getMessage)
    if (browserToProxyChannel.isConnected) {
      browserToProxyChannel.write(message)
    } else {
      if (e.getChannel.isConnected) {
        if (logger.isDebugEnabled()) logger.debug("Closing channel to remote server {}", e.getChannel)
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
    logger.debug("New channel opened: {}", e.getChannel)
    proxyConfig.allChannels.add(e.getChannel)
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    if (logger.isDebugEnabled()) logger.debug("Got closed event on : {}", e.getChannel)
    Utils.closeChannel(browserToProxyChannel)
  }


  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    if (logger.isDebugEnabled()) logger.debug("Caught exception on proxy -> web connection: " + e.getChannel, e.getCause)
    Utils.closeChannel(e.getChannel)
  }
}


class ConnectionRequestHandler(relayChannel: Channel)(implicit proxyConfig: ProxyConfig) extends SimpleChannelUpstreamHandler {
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    if (logger.isDebugEnabled()) logger.debug("ConnectionRequestHandler-{} receive message:\n {}", Array(ctx.getChannel, e.getMessage))
    val msg: ChannelBuffer = e.getMessage.asInstanceOf[ChannelBuffer]
    if (relayChannel.isConnected) {
      relayChannel.write(msg)
    }
  }

  override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    val ch: Channel = e.getChannel
    if (logger.isDebugEnabled()) logger.debug("CONNECT channel opened on: {}", ch)
    proxyConfig.allChannels.add(e.getChannel)
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    if (logger.isDebugEnabled()) logger.debug("Got closed event on : {}", e.getChannel)
    Utils.closeChannel(relayChannel)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn("Exception on: " + e.getChannel, e.getCause)
    Utils.closeChannel(e.getChannel)
  }
}
