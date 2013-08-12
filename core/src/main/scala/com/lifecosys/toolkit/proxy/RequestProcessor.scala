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
import org.jboss.netty.handler.ssl.SslHandler
import org.jboss.netty.handler.timeout.{ IdleStateEvent, IdleStateAwareChannelHandler, IdleStateHandler }
import org.jboss.netty.buffer.{ ChannelBufferInputStream, ChannelBuffer, ChannelBuffers }
import org.jboss.netty.handler.codec.compression.{ ZlibEncoder, ZlibDecoder }
import org.jboss.netty.handler.codec.serialization.{ ClassResolvers, ObjectDecoder, ObjectEncoder }
import org.jboss.netty.handler.codec.oneone.{ OneToOneDecoder, OneToOneEncoder }
import org.littleshoot.proxy.ProxyUtils
import org.apache.commons.io.IOUtils
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
  def httpRequestEncoder: HttpMessageEncoder
  def httpRequest: HttpRequest
  def browserToProxyContext: ChannelHandlerContext
}

abstract class HttpRequestProcessor(request: HttpRequest, browserChannelContext: ChannelHandlerContext)(implicit proxyConfig: ProxyConfig, connectHost: ConnectHost) extends RequestProcessor {

  override val httpRequest: HttpRequest = request
  implicit override val browserToProxyContext = browserChannelContext

  val browserChannel = browserToProxyContext.getChannel

  //Can't play online video since we send the full url for http request,
  // Exactly, we need use the relative url to access the remote server.
  if (!connectHost.needForward) httpRequest.setUri(Utils.stripHost(httpRequest.getUri))

  def process {
    logger.debug(s"Process request with $connectHost")
    hostToChannelFuture.remove(connectHost.host) match {
      case Some(channel) if channel.isConnected ⇒ {
        logger.debug(s"Use existed Proxy to server conntection: $channel, cached channels size: ${hostToChannelFuture.size}")
        channel.write(httpRequest)
      }
      case _ ⇒ {
        browserChannel.setReadable(false)
        val proxyToServerBootstrap = newClientBootstrap
        proxyToServerBootstrap.setFactory(proxyConfig.clientSocketChannelFactory)
        proxyToServerBootstrap.setPipelineFactory(proxyToServerPipeline)
        proxyToServerBootstrap.connect(connectHost.host.socketAddress).addListener(connectProcess _)
      }
    }
  }

  def connectProcess(future: ChannelFuture) {
    browserChannel.setReadable(true)

    if (future.isSuccess) {
      //                  hostToChannelFuture.put(connectHost.host, future.getChannel)
      future.getChannel().write(httpRequest).addListener {
        future: ChannelFuture ⇒ logger.debug(s"[${future.getChannel}] - Write request to remote server completed.")
      }
    } else {
      logger.debug(s"Close browser connection: $browserChannel")
      browserChannel.close()
      //      Utils.closeChannel(browserToProxyChannel)

    }
  }

  def proxyToServerPipeline = (pipeline: ChannelPipeline) ⇒ {
    //    pipeline.addFirst("logger", new LoggingHandler(proxyConfig.loggerLevel))
    //    if (connectHost.serverType != WebProxyType && connectHost.needForward && proxyConfig.proxyToServerSSLEnable) {
    //      val engine = proxyConfig.clientSSLContext.createSSLEngine
    //      engine.setUseClientMode(true)
    //      pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
    //    }
    //    if (connectHost.serverType == WebProxyType) {
    //      val engine = Utils.trustAllSSLContext.createSSLEngine
    //      engine.setUseClientMode(true)
    //      pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
    //    }

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
    //    pipeline.addLast("proxyServerToRemote-innerHttpChunkAggregator", new InnerHttpChunkAggregator())
    pipeline.addLast("proxyServerToRemote-idle", new IdleStateHandler(timer, 0, 0, 120))
    pipeline.addLast("proxyServerToRemote-idleAware", new IdleStateAwareChannelHandler {
      override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
        logger.debug(s"[${e.getChannel}}] - Channel idle, closing it.")
        Utils.closeChannel(e.getChannel)
      }
    })

  }

}

class DefaultHttpRequestProcessor(request: HttpRequest, browserChannelContext: ChannelHandlerContext)(implicit proxyConfig: ProxyConfig, connectHost: ConnectHost)
    extends HttpRequestProcessor(request, browserChannelContext) {

  override val httpRequestEncoder = new HttpRequestEncoder()
  override def proxyToServerPipeline = (pipeline: ChannelPipeline) ⇒ {
    if (connectHost.needForward && proxyConfig.proxyToServerSSLEnable) {
      val engine = proxyConfig.clientSSLContext.createSSLEngine
      engine.setUseClientMode(true)
      pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
    }
    super.proxyToServerPipeline(pipeline)
    pipeline.addLast("proxyServerToRemote-proxyToServerHandler", new NetHttpResponseRelayingHandler(browserChannel))
  }
}
class WebProxyHttpRequestProcessor(request: HttpRequest, browserChannelContext: ChannelHandlerContext)(implicit proxyConfig: ProxyConfig, connectHost: ConnectHost)
    extends HttpRequestProcessor(request, browserChannelContext) {

  require(connectHost.needForward, "The message must be need forward to remote WebProxy.")
  override val httpRequestEncoder = new WebProxyHttpRequestEncoder(connectHost, Host(httpRequest.getUri))

  override def connectProcess(future: ChannelFuture) {
    super.connectProcess(future)
  }

  override def proxyToServerPipeline = (pipeline: ChannelPipeline) ⇒ {
    if (proxyConfig.proxyToServerSSLEnable) {
      val engine = proxyConfig.clientSSLContext.createSSLEngine
      engine.setUseClientMode(true)
      pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
    }
    super.proxyToServerPipeline(pipeline)
    pipeline.addLast("proxyServerToRemote-webProxyResponseDecoder", new WebProxyResponseDecoder)
    pipeline.addLast("proxyServerToRemote-webProxyHttpResponseDecoder", new HttpResponseDecoder(8192 * 2, 8192 * 4, 8192 * 4))
    pipeline.addLast("proxyServerToRemote-proxyToServerHandler", new WebProxyHttpRelayingHandler(browserChannel))
  }
}

abstract class HttpsRequestProcessor(request: HttpRequest, browserChannelContext: ChannelHandlerContext)(implicit proxyConfig: ProxyConfig, connectHost: ConnectHost)
    extends RequestProcessor {
  require(request.getMethod == HttpMethod.CONNECT)

  override val httpRequest: HttpRequest = request

  implicit val browserToProxyContext = browserChannelContext

  //  require(connectHost.serverType != WebProxyType, "Web proxy don't support HTTPS access.")

  val browserChannel = browserToProxyContext.getChannel

  def process {
    logger.debug(s"Process request with $connectHost")
    hostToChannelFuture.get(connectHost.host) match {
      case Some(channel) if channel.isConnected ⇒ browserChannel.write(ChannelBuffers.copiedBuffer(Utils.connectProxyResponse.getBytes("UTF-8")))
      case None                                 ⇒ connect
    }
  }

  protected def connect

}

class NetHttpsRequestProcessor(request: HttpRequest, browserChannelContext: ChannelHandlerContext)(implicit proxyConfig: ProxyConfig, connectHost: ConnectHost)
    extends HttpsRequestProcessor(request, browserChannelContext) {

  val httpRequestEncoder = new HttpRequestEncoder()

  protected def connect {
    logger.debug("Starting new connection to: %s".format(connectHost.host))
    browserChannel.setReadable(false)
    createProxyToServerBootstrap.connect(connectHost.host.socketAddress).addListener(connectComplete _)
  }

  def createProxyToServerBootstrap = {
    val proxyToServerBootstrap = newClientBootstrap
    proxyToServerBootstrap.setFactory(proxyConfig.clientSocketChannelFactory)
    proxyToServerBootstrap setPipelineFactory proxyToServerPipeline
    proxyToServerBootstrap
  }

  def proxyToServerPipeline = (pipeline: ChannelPipeline) ⇒ {

    //    pipeline.addFirst("logger", new LoggingHandler(proxyConfig.loggerLevel))

    if (connectHost.serverType != WebProxyType && connectHost.needForward && proxyConfig.proxyToServerSSLEnable) {
      val engine = proxyConfig.clientSSLContext.createSSLEngine
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

    pipeline.addLast("proxyServerToRemote-idle", new IdleStateHandler(timer, 0, 0, 120))
    pipeline.addLast("proxyServerToRemote-idleAware", new IdleStateAwareChannelHandler {
      override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
        logger.debug(s"[${e.getChannel}}] - Channel idle, closing it.")
        Utils.closeChannel(e.getChannel)
      }
    })

    pipeline.addLast("proxyServerToRemote-connectionHandler", new NetHttpsRelayingHandler(browserChannel))
  }

  def connectComplete(future: ChannelFuture): Unit = {
    logger.debug("Connection successful: %s".format(future.getChannel))
    logger.debug(s"[${future.getChannel}] - Connect successful")

    if (!future.isSuccess) {
      logger.debug(s"Close browser connection: $browserChannel")
      Utils.closeChannel(browserChannel)
      return
    }

    val pipeline = browserChannel.getPipeline
    //Remove codec related handle for connect request, it's necessary for HTTPS.
    List("proxyServer-encoder", "proxyServer-decoder", "proxyServer-proxyHandler").foreach(pipeline remove _)
    //    if (connectHost.serverType == WebProxyType)
    //      pipeline.addLast("proxyServer-connectionHandler", new WebProxyHttpsRequestHandler(connectHost, Host(request.getUri)))
    //    else
    pipeline.addLast("proxyServer-connectionHandler", new NetHttpsRelayingHandler(future.getChannel))

    //    if (connectHost.serverType == WebProxyType) {
    //      future.getChannel.getPipeline.addBefore("proxyServerToRemote-connectionHandler", "proxyServerToRemote-decoder", new HttpResponseDecoder(8192 * 2, 8192 * 4, 8192 * 4))
    //    }
    //    future.getChannel.getPipeline.addBefore("proxyServerToRemote-connectionHandler", "proxyServerToRemote-encoder", httpRequestEncoder)

    def sendRequestToChainedProxy {
      future.getChannel.getPipeline.addBefore("proxyServerToRemote-connectionHandler", "proxyServerToRemote-encoder", httpRequestEncoder)

      future.getChannel.write(httpRequest).addListener {
        writeFuture: ChannelFuture ⇒
          {

            if (connectHost.serverType != WebProxyType) {
              writeFuture.getChannel.getPipeline.remove("proxyServerToRemote-encoder")
            }
            logger.debug(s"[${future.getChannel}] - Finished write request: $httpRequest")
          }
      }
    }

    if (connectHost.needForward)
      sendRequestToChainedProxy
    else browserChannel.write(ChannelBuffers.copiedBuffer(Utils.connectProxyResponse.getBytes("UTF-8"))).addListener {
      future: ChannelFuture ⇒
        logger.debug(s"[${future.getChannel}] - Finished write request: ${Utils.connectProxyResponse}")
    }

    browserChannel.setReadable(true)
  }

}
class WebProxyHttpsRequestProcessor(request: HttpRequest, browserChannelContext: ChannelHandlerContext)(implicit proxyConfig: ProxyConfig, connectHost: ConnectHost)
    extends HttpsRequestProcessor(request, browserChannelContext) {

  val httpRequestEncoder = new WebProxyHttpRequestEncoder(connectHost, Host(request.getUri))

  protected def connect {
    val pipeline = browserToProxyContext.getChannel.getPipeline
    //Remove codec related handle for connect request, it's necessary for HTTPS.
    List("proxyServer-encoder", "proxyServer-decoder", "proxyServer-proxyHandler").foreach(pipeline remove _)
    pipeline.addLast("proxyServer-connectionHandler", new WebProxyHttpsRequestHandler(connectHost, Host(request.getUri)))
    val connectionMessage = ChannelBuffers.wrappedBuffer(HttpMethod.CONNECT.getName.getBytes(Utils.UTF8))
    Channels.fireMessageReceived(browserChannel, connectionMessage)
  }
}

object ChannelHolder {
  val channels = scala.collection.mutable.Map[ChannelKey, Array[Channel]]()
  val pendingChannels = scala.collection.mutable.Map[ChannelKey, Array[Channel]]()

}

class WebProxyHttpsRequestHandler(connectHost: ConnectHost, proxyHost: Host)(implicit proxyConfig: ProxyConfig)
    extends SimpleChannelUpstreamHandler with Logging {

  def createProxyToServerBootstrap(implicit browserChannelContext: ChannelHandlerContext) = {
    val proxyToServerBootstrap = newClientBootstrap
    proxyToServerBootstrap.setFactory(proxyConfig.clientSocketChannelFactory)
    proxyToServerBootstrap.setPipelineFactory {
      pipeline: ChannelPipeline ⇒

        //        pipeline.addLast("logger", new LoggingHandler(InternalLogLevel.ERROR))

        if (proxyConfig.proxyToServerSSLEnable) {
          val engine = proxyConfig.clientSSLContext.createSSLEngine
          engine.setUseClientMode(true)
          pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
        }

        pipeline.addLast("proxyServerToRemote-decoder", new HttpResponseDecoder(8192 * 2, 8192 * 4, 8192 * 4))
        pipeline.addLast("proxyServerToRemote-encoder", new WebProxyHttpRequestEncoder(connectHost, proxyHost))

        pipeline.addLast("proxyServerToRemote-idle", new IdleStateHandler(timer, 0, 0, 120))
        pipeline.addLast("proxyServerToRemote-idleAware", new IdleStateAwareChannelHandler {
          override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
            logger.debug(s"[${e.getChannel}}] - Channel idle, closing it.")
            Utils.closeChannel(e.getChannel)
          }
        })

        pipeline.addLast("proxyServerToRemote-connectionHandler", new WebProxyHttpsRelayingHandler(browserChannelContext.getChannel))
    }
    proxyToServerBootstrap
  }

  val channels = scala.collection.mutable.MutableList[Channel]()
  private[this] var channel: Option[Channel] = None
  var data: Option[DataHolder] = None

  var buffers = scala.collection.mutable.ArrayBuffer[ChannelBuffer]()
  var phase: HttpsPhase = Init
  override def messageReceived(browserChannelContext: ChannelHandlerContext, e: MessageEvent) {
    logger.debug(s"${browserChannelContext.getChannel} Receive message:\n ${Utils.formatMessage(e.getMessage)}")
    val requestMessage = e.getMessage.asInstanceOf[ChannelBuffer]

    val browserChannel = browserChannelContext.getChannel
    browserChannel.setReadable(false)
    createProxyToServerBootstrap(browserChannelContext).connect(connectHost.host.socketAddress).addListener(connectComplete _)

    def connectComplete(future: ChannelFuture): Unit = {
      logger.debug(s"[${future.getChannel}] - Connect successful.")
      browserChannel.setReadable(true)
      if (!future.isSuccess) {
        logger.debug(s"Close browser connection: $browserChannel")
        Utils.closeChannel(browserChannel)
        return
      }

      channel = Some(future.getChannel)
      future.getChannel.write(requestMessage).addListener {
        writeFuture: ChannelFuture ⇒
          {
            logger.debug(s"[${future.getChannel}] - Finished write request: ${Utils.formatMessage(requestMessage)}")
            buffers.clear()
          }
      }
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.debug(s"[${e.getChannel}] - closed.")
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn(s"[${e.getChannel}] - Got exception.", e.getCause)
    e.getCause match {
      case closeException: ClosedChannelException ⇒ //Just ignore it
      case exception                              ⇒ Utils.closeChannel(e.getChannel)
    }
  }
}

