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

import org.jboss.netty.handler.codec.http._
import org.jboss.netty.channel._
import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.handler.ssl.SslHandler
import org.jboss.netty.handler.timeout.{ IdleStateEvent, IdleStateAwareChannelHandler, IdleStateHandler }
import org.jboss.netty.buffer.{ ChannelBufferInputStream, ChannelBuffer, ChannelBuffers }
import org.jboss.netty.handler.codec.compression.{ ZlibEncoder, ZlibDecoder }
import org.jboss.netty.handler.codec.serialization.{ ClassResolvers, ObjectDecoder, ObjectEncoder }
import org.jboss.netty.handler.codec.oneone.{ OneToOneDecoder, OneToOneEncoder }
import org.littleshoot.proxy.ProxyUtils
import org.apache.commons.io.IOUtils
import org.jboss.netty.handler.codec.http
import org.jboss.netty.channel.socket.nio.NioSocketChannelConfig
import java.nio.channels.ClosedChannelException
import com.typesafe.scalalogging.slf4j.Logging

/**
 *
 *
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 1/1/13 5:50 PM
 */

trait RequestProcessor extends Logging {
  def process

  val httpRequest: HttpRequest
  val browserToProxyContext: ChannelHandlerContext
}

class DefaultRequestProcessor(request: HttpRequest, browserToProxyChannelContext: ChannelHandlerContext)(implicit proxyConfig: ProxyConfig) extends RequestProcessor {

  override val httpRequest: HttpRequest = request
  override val browserToProxyContext = browserToProxyChannelContext

  val connectHost = proxyConfig.getChainProxyManager.getConnectHost(httpRequest.getUri).get
  val browserToProxyChannel = browserToProxyContext.getChannel

  //Can't play online video since we send the full url for http request,
  // Exactly, we need use the relative url to access the remote server.
  if (!connectHost.needForward) httpRequest.setUri(Utils.stripHost(httpRequest.getUri))

  val httpRequestEncoder = connectHost.serverType match {
    case WebProxyType ⇒ new WebProxyHttpRequestEncoder(connectHost, Host(httpRequest.getUri))
    case _            ⇒ new HttpRequestEncoder()
  }

  def process {
    logger.debug(s"Process request with $connectHost")
    hostToChannelFuture.remove(connectHost.host) match {
      case Some(channel) if channel.isConnected ⇒ {
        logger.error(s"###########Use existed Proxy to server conntection: $channel################## Size ${hostToChannelFuture.size}##################")
        channel.write(httpRequest)
      }
      case _ ⇒ {
        browserToProxyChannel.setReadable(false)
        val proxyToServerBootstrap = newClientBootstrap
        proxyToServerBootstrap.setFactory(proxyConfig.clientSocketChannelFactory)
        proxyToServerBootstrap.setPipelineFactory(proxyToServerPipeline)
        proxyToServerBootstrap.connect(connectHost.host.socketAddress).addListener(connectProcess _)
      }
    }
  }

  def connectProcess(future: ChannelFuture) {
    browserToProxyChannel.setReadable(true)

    if (future.isSuccess) {
      //                  hostToChannelFuture.put(connectHost.host, future.getChannel)
      future.getChannel().write(httpRequest).addListener {
        future: ChannelFuture ⇒ logger.debug("Write request to remote server %s completed.".format(future.getChannel))
      }
    } else {
      logger.debug("Close browser connection...")
      browserToProxyChannel.close()
      //      Utils.closeChannel(browserToProxyChannel)

    }
  }

  def proxyToServerPipeline = (pipeline: ChannelPipeline) ⇒ {
    //pipeline.addLast("logger", new LoggingHandler(proxyConfig.loggerLevel))
    if (connectHost.serverType != WebProxyType && connectHost.needForward && proxyConfig.proxyToServerSSLEnable) {
      val engine = proxyConfig.clientSSLContext.createSSLEngine
      engine.setUseClientMode(true)
      pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
    }
    if (connectHost.serverType == WebProxyType) {
      val engine = Utils.trustAllSSLContext.createSSLEngine
      engine.setUseClientMode(true)
      pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
    }

    if (connectHost.serverType != WebProxyType && connectHost.needForward) {
      pipeline.addLast("proxyServerToRemote-inflater", new IgnoreEmptyBufferZlibDecoder)
      pipeline.addLast("proxyServerToRemote-objectDecoder", new ObjectDecoder(ClassResolvers.weakCachingResolver(null)))
      pipeline.addLast("proxyServerToRemote-decrypt", new DecryptDecoder)

      pipeline.addLast("proxyServerToRemote-deflater", new IgnoreEmptyBufferZlibEncoder)
      pipeline.addLast("proxyServerToRemote-objectEncoder", new ObjectEncoder)
      pipeline.addLast("proxyServerToRemote-encrypt", new EncryptEncoder)
    }
    pipeline.addLast("proxyServerToRemote-httpResponseDecoder", new HttpResponseDecoder(8192 * 2, 8192 * 4, 8192 * 4))
    pipeline.addLast("proxyServerToRemote-httpResponseEncoder", httpRequestEncoder)
    pipeline.addLast("proxyServerToRemote-innerHttpChunkAggregator", new InnerHttpChunkAggregator())
    pipeline.addLast("proxyServerToRemote-idle", new IdleStateHandler(timer, 0, 0, 120))
    pipeline.addLast("proxyServerToRemote-idleAware", new IdleStateAwareChannelHandler {
      override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
        logger.debug("Channel idle........%s".format(e.getChannel))
        Utils.closeChannel(e.getChannel)
      }
    })
    pipeline.addLast("proxyServerToRemote-proxyToServerHandler", new HttpRelayingHandler(browserToProxyChannel))
  }

}

class ConnectionRequestProcessor(request: HttpRequest, browserToProxyChannelContext: ChannelHandlerContext)(implicit proxyConfig: ProxyConfig) extends RequestProcessor {
  override val httpRequest: HttpRequest = request
  val browserToProxyContext = browserToProxyChannelContext

  val connectHost = proxyConfig.getChainProxyManager.getConnectHost(httpRequest.getUri).get

  val httpRequestEncoder = connectHost.serverType match {
    case WebProxyType ⇒ new WebProxyHttpRequestEncoder(connectHost, Host(request.getUri))
    case _            ⇒ new HttpRequestEncoder()
  }
  //  require(connectHost.serverType != WebProxyType, "Web proxy don't support HTTPS access.")

  val browserToProxyChannel = browserToProxyContext.getChannel

  def process {
    logger.debug("##########ConnectionRequestProcessor################Process request with %s".format((connectHost.host, connectHost.needForward)))
    hostToChannelFuture.get(connectHost.host) match {
      case Some(channel) if channel.isConnected ⇒ browserToProxyChannel.write(ChannelBuffers.copiedBuffer(Utils.connectProxyResponse.getBytes("UTF-8")))
      case None ⇒ {
        if (connectHost.serverType == WebProxyType) {
          val pipeline = browserToProxyChannel.getPipeline
          //Remove codec related handle for connect request, it's necessary for HTTPS.
          List("proxyServer-encoder", "proxyServer-decoder", "proxyServer-proxyHandler").foreach(pipeline remove _)
          pipeline.addLast("proxyServer-connectionHandler", new WebProxyConnectionRequestHandler(connectHost, Host(request.getUri)))

          browserToProxyChannel.write(ChannelBuffers.copiedBuffer(Utils.connectProxyResponse.getBytes("UTF-8"))).addListener {
            future: ChannelFuture ⇒ logger.debug("Finished write request to %s \n %s ".format(future.getChannel, Utils.connectProxyResponse))
          }

        } else {
          logger.debug("Starting new connection to: %s".format(connectHost.host))
          browserToProxyChannel.setReadable(false)
          createProxyToServerBootstrap.connect(connectHost.host.socketAddress).addListener(connectComplete _)

        }

      }
    }
  }

  def createProxyToServerBootstrap = {
    val proxyToServerBootstrap = newClientBootstrap
    proxyToServerBootstrap.setFactory(proxyConfig.clientSocketChannelFactory)
    proxyToServerBootstrap.setPipelineFactory {
      pipeline: ChannelPipeline ⇒

        //pipeline.addLast("logger", new LoggingHandler(proxyConfig.loggerLevel))

        if (connectHost.serverType != WebProxyType && connectHost.needForward && proxyConfig.proxyToServerSSLEnable) {
          val engine = proxyConfig.clientSSLContext.createSSLEngine
          engine.setUseClientMode(true)
          pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
        }

        //        if (connectHost.serverType == WebProxyType) {
        //          val engine = Utils.trustAllSSLContext.createSSLEngine
        //          engine.setUseClientMode(true)
        //          pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
        //        }

        if (connectHost.serverType != WebProxyType && connectHost.needForward) {
          pipeline.addLast("proxyServerToRemote-inflater", new IgnoreEmptyBufferZlibDecoder)
          pipeline.addLast("proxyServerToRemote-objectDecoder", new ObjectDecoder(ClassResolvers.weakCachingResolver(null)))
          pipeline.addLast("proxyServerToRemote-decrypt", new DecryptDecoder)

          pipeline.addLast("proxyServerToRemote-deflater", new IgnoreEmptyBufferZlibEncoder)
          pipeline.addLast("proxyServerToRemote-objectEncoder", new ObjectEncoder)
          pipeline.addLast("proxyServerToRemote-encrypt", new EncryptEncoder)
        }

        pipeline.addLast("proxyServerToRemote-idle", new IdleStateHandler(timer, 0, 0, 120))
        pipeline.addLast("proxyServerToRemote-idleAware", new IdleStateAwareChannelHandler {
          override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
            logger.debug("Channel idle........%s".format(e.getChannel))
            Utils.closeChannel(e.getChannel)
          }
        })

        pipeline.addLast("proxyServerToRemote-connectionHandler", new ConnectionRequestHandler(browserToProxyChannel))
    }
    proxyToServerBootstrap
  }

  def connectComplete(future: ChannelFuture): Unit = {
    logger.debug("Connection successful: %s".format(future.getChannel))

    if (!future.isSuccess) {
      logger.debug("Close browser connection...")
      Utils.closeChannel(browserToProxyChannel)
      return
    }

    val pipeline = browserToProxyChannel.getPipeline
    //Remove codec related handle for connect request, it's necessary for HTTPS.
    List("proxyServer-encoder", "proxyServer-decoder", "proxyServer-proxyHandler").foreach(pipeline remove _)
    //    if (connectHost.serverType == WebProxyType)
    //      pipeline.addLast("proxyServer-connectionHandler", new WebProxyConnectionRequestHandler(connectHost, Host(request.getUri)))
    //    else
    pipeline.addLast("proxyServer-connectionHandler", new ConnectionRequestHandler(future.getChannel))

    //    if (connectHost.serverType == WebProxyType) {
    //      future.getChannel.getPipeline.addBefore("proxyServerToRemote-connectionHandler", "proxyServerToRemote-decoder", new HttpResponseDecoder(8192 * 2, 8192 * 4, 8192 * 4))
    //    }
    //    future.getChannel.getPipeline.addBefore("proxyServerToRemote-connectionHandler", "proxyServerToRemote-encoder", httpRequestEncoder)

    def sendRequestToChainedProxy {

      if (connectHost.serverType == WebProxyType) {
        future.getChannel.getPipeline.addBefore("proxyServerToRemote-connectionHandler", "proxyServerToRemote-decoder", new HttpResponseDecoder(8192 * 2, 8192 * 4, 8192 * 4))
      }
      future.getChannel.getPipeline.addBefore("proxyServerToRemote-connectionHandler", "proxyServerToRemote-encoder", httpRequestEncoder)

      future.getChannel.write(httpRequest).addListener {
        writeFuture: ChannelFuture ⇒
          {

            if (connectHost.serverType != WebProxyType) {
              writeFuture.getChannel.getPipeline.remove("proxyServerToRemote-encoder")
            }
            logger.debug("Finished write request to %s\n %s ".format(future.getChannel, httpRequest))
          }
      }
    }

    if (connectHost.needForward)
      sendRequestToChainedProxy
    else browserToProxyChannel.write(ChannelBuffers.copiedBuffer(Utils.connectProxyResponse.getBytes("UTF-8"))).addListener {
      future: ChannelFuture ⇒ logger.debug("Finished write request to %s \n %s ".format(future.getChannel, Utils.connectProxyResponse))
    }

    browserToProxyChannel.setReadable(true)
  }
}

class WebProxyConnectionRequestHandler(connectHost: ConnectHost, proxyHost: Host)(implicit proxyConfig: ProxyConfig)
    extends SimpleChannelUpstreamHandler with Logging {

  def createProxyToServerBootstrap(browserToProxyChannel: Channel) = {
    val proxyToServerBootstrap = newClientBootstrap
    proxyToServerBootstrap.setFactory(proxyConfig.clientSocketChannelFactory)
    proxyToServerBootstrap.setPipelineFactory {
      pipeline: ChannelPipeline ⇒

        //pipeline.addLast("logger", new LoggingHandler(proxyConfig.loggerLevel))

        //        val engine = Utils.trustAllSSLContext.createSSLEngine
        //        engine.setUseClientMode(true)
        //        pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))

        pipeline.addLast("proxyServerToRemote-decoder", new HttpResponseDecoder(8192 * 2, 8192 * 4, 8192 * 4))
        pipeline.addLast("proxyServerToRemote-encoder", new WebProxyHttpRequestEncoder(connectHost, proxyHost))

        pipeline.addLast("proxyServerToRemote-idle", new IdleStateHandler(timer, 0, 0, 120))
        pipeline.addLast("proxyServerToRemote-idleAware", new IdleStateAwareChannelHandler {
          override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
            logger.debug("Channel idle........%s".format(e.getChannel))
            Utils.closeChannel(e.getChannel)
          }
        })

        pipeline.addLast("proxyServerToRemote-connectionHandler", new ConnectionRequestHandler(browserToProxyChannel))
    }
    proxyToServerBootstrap
  }

  val channels = scala.collection.mutable.MutableList[Channel]()
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.debug("=====%s receive message:\n %s".format(ctx.getChannel, e.getMessage))

    logger.error(s"#################################${ctx.getChannel}###############################################")
    logger.error(s"Length: ${e.getMessage.asInstanceOf[ChannelBuffer].readableBytes()}\n${Utils.formatBuffer(e.getMessage.asInstanceOf[ChannelBuffer])}")
    logger.error(s"################################################################################")

    val browserToProxyChannel = e.getChannel
    browserToProxyChannel.setReadable(false)
    createProxyToServerBootstrap(e.getChannel).connect(connectHost.host.socketAddress).addListener(connectComplete _)

    def connectComplete(future: ChannelFuture): Unit = {
      logger.debug("####################Connection successful: %s".format(future.getChannel))
      browserToProxyChannel.setReadable(true)

      if (!future.isSuccess) {
        logger.debug("Close browser connection...")
        Utils.closeChannel(browserToProxyChannel)
        return
      }

      val channel = future.getChannel
      synchronized(channels += channel)
      channel.write(e.getMessage).addListener {
        writeFuture: ChannelFuture ⇒
          {
            //            writeFuture.getChannel.getPipeline.remove("proxyServerToRemote-encoder")
            logger.debug("####################Finished write request to %s\n %s ".format(future.getChannel, e.getMessage))
          }
      }
    }

  }

  override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    val ch: Channel = e.getChannel
    logger.debug("CONNECT channel opened on: %s".format(ch))
    proxyConfig.allChannels.add(e.getChannel)
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.debug("Got closed event on : %s".format(e.getChannel))
    //    channels.foreach(Utils.closeChannel(_))
    //    synchronized(channels.clear())
    //        Utils.closeChannel(relayChannel)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn("Caught exception on : %s".format(e.getChannel), e.getCause)
    e.getCause match {
      case closeException: ClosedChannelException ⇒ //Just ignore it
      case exception                              ⇒ Utils.closeChannel(e.getChannel)
    }
  }
}

class InnerHttpChunkAggregator(maxContentLength: Int = 1024 * 128) extends HttpChunkAggregator(maxContentLength) {
  var cumulatedThunk: Option[HttpChunk] = None

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    e.getMessage match {
      case response: HttpMessage ⇒ ctx.sendUpstream(e)
      case chunk: HttpChunk if chunk.isLast ⇒ {
        if (cumulatedThunk isDefined) {
          Channels.fireMessageReceived(ctx, cumulatedThunk.get, e.getRemoteAddress)
          cumulatedThunk = None
        }
        ctx.sendUpstream(e)
      }
      case chunk: HttpChunk ⇒ {

        if (!cumulatedThunk.isDefined)
          cumulatedThunk = Some(chunk)
        else
          cumulatedThunk.get.setContent(ChannelBuffers.wrappedBuffer(cumulatedThunk.get.getContent, chunk.getContent))

        if (cumulatedThunk.get.getContent.readableBytes() > maxContentLength) {
          Channels.fireMessageReceived(ctx, cumulatedThunk.get, e.getRemoteAddress)
          cumulatedThunk = None
        }
      }
      case _ ⇒ ctx.sendUpstream(e)
    }
  }
}

/**
 * We need it to wrapper the buffer byte array to avoid the padding and block size process.
 * @param data
 */
sealed case class EncryptDataWrapper(data: Array[Byte])

class EncryptEncoder extends OneToOneEncoder {
  override def encode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = if (msg.isInstanceOf[ChannelBuffer])
    EncryptDataWrapper(Utils.cryptor.encrypt(ChannelBuffers.copiedBuffer(msg.asInstanceOf[ChannelBuffer]).array()))
  else
    msg
}

class DecryptDecoder extends OneToOneDecoder {
  def decode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = msg match {
    case EncryptDataWrapper(data) ⇒ ChannelBuffers.copiedBuffer(Utils.cryptor.decrypt(data))
    case _                        ⇒ msg
  }
}

class IgnoreEmptyBufferZlibEncoder extends ZlibEncoder {
  override def encode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = msg match {
    case cb: ChannelBuffer if (cb.hasArray) ⇒ super.encode(ctx, channel, msg).asInstanceOf[ChannelBuffer]
    case _                                  ⇒ msg
  }
}

class IgnoreEmptyBufferZlibDecoder extends ZlibDecoder {
  override def decode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = msg match {
    case cb: ChannelBuffer if (cb.hasArray) ⇒ super.decode(ctx, channel, msg).asInstanceOf[ChannelBuffer]
    case _                                  ⇒ msg
  }
}

//
//class WebProxyResponseEncoder extends OneToOneEncoder {
//   def encode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) =msg match {
//     case request: HttpRequest => {
//       val encodedProxyRequest=new HttpRequestEncoder().encode(ctx, channel, ProxyUtils.copyHttpRequest(request, false)).asInstanceOf[ChannelBuffer]
//       val toSendRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/proxy")
//       toSendRequest.setHeader(HttpHeaders.Names.HOST, host.host.host)
//       toSendRequest.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE)
//       toSendRequest.addHeader(HttpHeaders.Names.ACCEPT, "application/octet-stream")
//       toSendRequest.addHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
//       toSendRequest.setHeader(HttpHeaders.Names.USER_AGENT, "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:22.0) Gecko/20100101 Firefox/22.0")
//       toSendRequest.setHeader("proxyHost", Host(request.getUri).toString)
//       toSendRequest.setContent(encodedProxyRequest)
//       super.encode(ctx, channel, toSendRequest)
//
//     }
//   }
//
//     if (msg.isInstanceOf[ChannelBuffer])
//    EncryptDataWrapper(Utils.cryptor.encrypt(ChannelBuffers.copiedBuffer(msg.asInstanceOf[ChannelBuffer]).array()))
//  else
//    msg
//}
//
class WebProxyHttpRequestEncoder(connectHost: ConnectHost, proxyHost: Host)
    extends HttpRequestEncoder with Logging {

  override def encode(ctx: ChannelHandlerContext, channel: Channel, msg: Any): AnyRef = {
    val sentHttpRequest = msg match {
      case request: HttpRequest ⇒ {
        val encodedProxyRequest = super.encode(ctx, channel, ProxyUtils.copyHttpRequest(request, false)).asInstanceOf[ChannelBuffer]
        logger.debug("Encoded proxy request:\n" + IOUtils.toString(new ChannelBufferInputStream(ChannelBuffers.copiedBuffer(encodedProxyRequest))))
        val toSendRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/proxy")
        toSendRequest.setHeader(HttpHeaders.Names.HOST, connectHost.host.host)
        toSendRequest.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE)
        toSendRequest.addHeader(HttpHeaders.Names.ACCEPT, "application/octet-stream")
        toSendRequest.addHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
        toSendRequest.setHeader(HttpHeaders.Names.USER_AGENT, "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:22.0) Gecko/20100101 Firefox/22.0")
        toSendRequest.setHeader("proxyHost", proxyHost.toString)
        toSendRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, encodedProxyRequest.readableBytes().toString)
        toSendRequest.setContent(encodedProxyRequest)
        toSendRequest
      }
      case e: ChannelBuffer ⇒
        val toSendRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/proxy")
        toSendRequest.setHeader(HttpHeaders.Names.HOST, connectHost.host.host)
        toSendRequest.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE)
        toSendRequest.addHeader(HttpHeaders.Names.ACCEPT, "application/octet-stream")
        toSendRequest.addHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
        toSendRequest.setHeader(HttpHeaders.Names.USER_AGENT, "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:22.0) Gecko/20100101 Firefox/22.0")
        toSendRequest.setHeader("proxyHost", proxyHost.toString)
        toSendRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, e.readableBytes().toString)
        toSendRequest.setContent(e)
        toSendRequest
      case e ⇒ e
    }
    super.encode(ctx, channel, sentHttpRequest)
  }

}

