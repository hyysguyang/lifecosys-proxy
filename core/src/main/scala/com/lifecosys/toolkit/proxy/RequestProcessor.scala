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
import org.jboss.netty.buffer.{ ChannelBuffer, ChannelBuffers }
import org.jboss.netty.handler.codec.serialization.{ ClassResolvers, ObjectDecoder, ObjectEncoder }
import java.nio.channels.ClosedChannelException
import com.typesafe.scalalogging.slf4j.Logging
import scala.util.Try
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.jboss.netty.handler.logging.LoggingHandler
import org.jboss.netty.logging.InternalLogLevel

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
}

abstract class HttpRequestProcessor(request: HttpRequest, browserChannel: Channel)(implicit proxyConfig: ProxyConfig, connectHost: ConnectHost) extends RequestProcessor {

  override val httpRequest: HttpRequest = request

  Option(browserChannel.getAttachment) match {
    case Some(requestCounter) ⇒ {
      require(requestCounter.isInstanceOf[AtomicInteger], "Request counter must be integer")
      requestCounter.asInstanceOf[AtomicInteger].incrementAndGet()
    }
    case None ⇒ browserChannel.setAttachment(new AtomicInteger(1))
  }
  //
  //  synchronized {
  //    val index = Option(browserChannel.getAttachment).getOrElse().asInstanceOf[AtomicInteger]
  //    Option(ctx.getChannel.getAttachment).getOrElse(new AtomicInteger).asInstanceOf[AtomicInteger].incrementAndGet()
  //    ctx.getChannel.setAttachment(index)
  //  }

  //Can't play online video since we send the full url for http request,
  // Exactly, we need use the relative url to access the remote server.
  if (!connectHost.needForward) httpRequest.setUri(Utils.stripHost(httpRequest.getUri))

  def process = {
    logger.info(s"Process request with $connectHost")
    createConnectionAndWriteRequest
    //    HttpChannelManager.get(connectHost.host.socketAddress) match {
    //      case Some(channelFuture) if channelFuture.getChannel.isConnected ⇒ {
    //        val channel = channelFuture.getChannel
    //        logger.debug(s"$HttpChannelManager")
    //        logger.info(s"Use existed channel ${channel}")
    //
    //        channel.setAttachment(UUID.randomUUID())
    //        //        DefaultRequestManager.add(Request(channel.getAttachment.toString, browserChannel, channel))
    //
    //        adjustPipelineForReused(channel)
    //        channel.write(httpRequest).addListener {
    //          writeFuture: ChannelFuture ⇒ logger.debug(s"[${channel}] - Finished write request: ${Utils.formatMessage(httpRequest)}")
    //        }
    //      }
    //      case _ ⇒ createConnectionAndWriteRequest
    //    }
  }

  def createConnectionAndWriteRequest {
    browserChannel.setReadable(false)
    val proxyToServerBootstrap = newClientBootstrap
    proxyToServerBootstrap.setFactory(proxyConfig.clientSocketChannelFactory)
    proxyToServerBootstrap.setPipelineFactory(proxyToServerPipeline)
    proxyToServerBootstrap.connect(connectHost.host.socketAddress).addListener(connectProcess _)
  }

  def adjustPipelineForReused(channel: Channel)

  def connectProcess(future: ChannelFuture) {
    browserChannel.setReadable(true)

    if (!future.isSuccess) {
      logger.info(s"Close browser connection: $browserChannel")
      Utils.closeChannel(browserChannel)
      return
    }

    future.getChannel.setAttachment(UUID.randomUUID())
    //    DefaultRequestManager.add(Request(future.getChannel.getAttachment.toString, browserChannel, future.getChannel))
    //    logger.error(s">>>>>>>>>>>>>>>>>[${browserChannel}] --[${future.getChannel.getAttachment}]--  [${future.getChannel}] - Connect successful.")

    connectSuccessProcess(future)
  }

  def connectSuccessProcess(future: ChannelFuture) {

    logger.info(s"[${future.getChannel}] - Connect successful.")
    writeRequest(future)
  }

  def writeRequest(future: ChannelFuture) {
    future.getChannel().write(httpRequest).addListener {
      writeFuture: ChannelFuture ⇒
        logger.debug(s"[${future.getChannel}] - Finished write request: ${Utils.formatMessage(httpRequest)}")
    }
  }

  def proxyToServerPipeline = (pipeline: ChannelPipeline) ⇒ {
    //    pipeline.addLast("logger", new LoggingHandler(InternalLogLevel.ERROR, true))
    //    if (connectHost.needForward && proxyConfig.proxyToServerSSLEnable) {
    //      val engine = proxyConfig.clientSSLContext.createSSLEngine
    //      engine.setUseClientMode(true)
    //      pipeline.addFirst("proxyServerToRemote-ssl", new SslHandler(engine))
    //    }

    //    pipeline.addLast("proxyServerToRemote-innerHttpChunkAggregator", new InnerHttpChunkAggregator())

  }

}

class NettyWebProxyHttpRequestProcessor(request: HttpRequest, browserChannel: Channel)(implicit proxyConfig: ProxyConfig, connectHost: ConnectHost)
    extends HttpRequestProcessor(request, browserChannel) {

  override val httpRequestEncoder = new HttpRequestEncoder()

  override def adjustPipelineForReused(channel: Channel) {
    channel.getPipeline.replace(classOf[NetHttpResponseRelayingHandler], "proxyServerToRemote-proxyToServerHandler", new NetHttpResponseRelayingHandler(browserChannel))
  }

  override def writeRequest(future: ChannelFuture) {

    val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
    response.setChunked(true)
    browserChannel.write(response)

    httpRequest.setUri(Utils.stripHost(httpRequest.getUri))

    future.getChannel().write(httpRequest).addListener {
      writeFuture: ChannelFuture ⇒
        logger.debug(s"[${future.getChannel}] - Finished write request: ${Utils.formatMessage(httpRequest)}")
    }
  }

  override def proxyToServerPipeline = (pipeline: ChannelPipeline) ⇒ {

    //    if (connectHost.needForward) {
    //      pipeline.addLast("proxyServerToRemote-inflater", new IgnoreEmptyBufferZlibDecoder)
    //      pipeline.addLast("proxyServerToRemote-objectDecoder", new ObjectDecoder(ClassResolvers.weakCachingResolver(null)))
    //      pipeline.addLast("proxyServerToRemote-decrypt", new DecryptDecoder)
    //
    //      pipeline.addLast("proxyServerToRemote-deflater", new IgnoreEmptyBufferZlibEncoder)
    //      pipeline.addLast("proxyServerToRemote-objectEncoder", new ObjectEncoder)
    //      pipeline.addLast("proxyServerToRemote-encrypt", new EncryptEncoder)
    //    }

    super.proxyToServerPipeline(pipeline)

    //    if (proxyConfig.isLocal) {
    //      val proxyHost = Try(Host(httpRequest.getUri)).getOrElse(Host(httpRequest.getHeader(HttpHeaders.Names.HOST)))
    //      pipeline.addLast("proxyServerToRemote-httpResponseDecoder", new HttpResponseDecoder(DEFAULT_BUFFER_SIZE * 2, DEFAULT_BUFFER_SIZE * 4, DEFAULT_BUFFER_SIZE * 4))
    //      pipeline.addLast("proxyServerToRemote-webProxyResponseBufferDecoder", new WebProxyResponseDecoder(browserChannel))
    //      pipeline.addLast("proxyServerToRemote-webProxyResponseDecoder", new HttpResponseDecoder(DEFAULT_BUFFER_SIZE * 2, DEFAULT_BUFFER_SIZE * 4, DEFAULT_BUFFER_SIZE * 4))
    //      pipeline.addLast("proxyServerToRemote-httpRequestEncoder", new WebProxyHttpRequestEncoder(connectHost, proxyHost, browserChannel))
    //    } else {
    //      //      pipeline.addLast("proxyServerToRemote-httpResponseDecoder", new HttpResponseDecoder(DEFAULT_BUFFER_SIZE * 2, DEFAULT_BUFFER_SIZE * 4, DEFAULT_BUFFER_SIZE * 4))
    //      pipeline.addLast("proxyServerToRemote-httpRequestEncoder", new HttpRequestEncoder())
    //
    //    }

    addIdleChannelHandler(pipeline)
    pipeline.addLast("proxyServerToRemote-httpRequestEncoder", new HttpRequestEncoder())
    pipeline.addLast("proxyServerToRemote-proxyToServerHandler", new NettyWebProxyServerHttpResponseRelayingHandler(browserChannel))

  }
}

class NettyWebProxyClientHttpRequestProcessor(request: HttpRequest, browserChannel: Channel)(implicit proxyConfig: ProxyConfig, connectHost: ConnectHost)
    extends HttpRequestProcessor(request, browserChannel) {

  override val httpRequestEncoder = new HttpRequestEncoder()

  override def adjustPipelineForReused(channel: Channel) {
    channel.getPipeline.replace(classOf[NetHttpResponseRelayingHandler], "proxyServerToRemote-proxyToServerHandler", new NetHttpResponseRelayingHandler(browserChannel))
  }

  override def proxyToServerPipeline = (pipeline: ChannelPipeline) ⇒ {
    pipeline.addLast("logger", new LoggingHandler(InternalLogLevel.ERROR, true))
    if (connectHost.needForward && proxyConfig.proxyToServerSSLEnable) {
      val engine = proxyConfig.clientSSLContext.createSSLEngine
      engine.setUseClientMode(true)
      pipeline.addFirst("proxyServerToRemote-ssl", new SslHandler(engine))
    }

    //    if (connectHost.needForward) {
    //      pipeline.addLast("proxyServerToRemote-inflater", new IgnoreEmptyBufferZlibDecoder)
    //      pipeline.addLast("proxyServerToRemote-objectDecoder", new ObjectDecoder(ClassResolvers.weakCachingResolver(null)))
    //      pipeline.addLast("proxyServerToRemote-decrypt", new DecryptDecoder)
    //
    //      pipeline.addLast("proxyServerToRemote-deflater", new IgnoreEmptyBufferZlibEncoder)
    //      pipeline.addLast("proxyServerToRemote-objectEncoder", new ObjectEncoder)
    //      pipeline.addLast("proxyServerToRemote-encrypt", new EncryptEncoder)
    //    }

    val proxyHost = Try(Host(httpRequest.getUri)).getOrElse(Host(httpRequest.getHeader(HttpHeaders.Names.HOST)))
    pipeline.addLast("proxyServerToRemote-httpResponseDecoder", new HttpResponseDecoder(DEFAULT_BUFFER_SIZE * 2, DEFAULT_BUFFER_SIZE * 4, DEFAULT_BUFFER_SIZE * 4))
    pipeline.addLast("proxyServerToRemote-webProxyResponseBufferDecoder", new WebProxyResponseDecoder(browserChannel))
    pipeline.addLast("proxyServerToRemote-webProxyResponseDecoder", new HttpResponseDecoder(DEFAULT_BUFFER_SIZE * 2, DEFAULT_BUFFER_SIZE * 4, DEFAULT_BUFFER_SIZE * 4))
    pipeline.addLast("proxyServerToRemote-httpRequestEncoder", new WebProxyHttpRequestEncoder(connectHost, proxyHost, browserChannel))
    pipeline.addLast("proxyServerToRemote-proxyToServerHandler", new NetHttpResponseRelayingHandler(browserChannel))
  }
}
class DefaultHttpRequestProcessor(request: HttpRequest, browserChannel: Channel)(implicit proxyConfig: ProxyConfig, connectHost: ConnectHost)
    extends HttpRequestProcessor(request, browserChannel) {

  override val httpRequestEncoder = new HttpRequestEncoder()

  override def adjustPipelineForReused(channel: Channel) {
    channel.getPipeline.replace(classOf[NetHttpResponseRelayingHandler], "proxyServerToRemote-proxyToServerHandler", new NetHttpResponseRelayingHandler(browserChannel))
  }

  override def proxyToServerPipeline = (pipeline: ChannelPipeline) ⇒ {

    if (connectHost.needForward && proxyConfig.proxyToServerSSLEnable) {
      val engine = proxyConfig.clientSSLContext.createSSLEngine
      engine.setUseClientMode(true)
      pipeline.addFirst("proxyServerToRemote-ssl", new SslHandler(engine))
    }

    if (connectHost.needForward) {
      pipeline.addLast("proxyServerToRemote-inflater", new IgnoreEmptyBufferZlibDecoder)
      pipeline.addLast("proxyServerToRemote-objectDecoder", new ObjectDecoder(ClassResolvers.weakCachingResolver(null)))
      pipeline.addLast("proxyServerToRemote-decrypt", new DecryptDecoder)

      pipeline.addLast("proxyServerToRemote-deflater", new IgnoreEmptyBufferZlibEncoder)
      pipeline.addLast("proxyServerToRemote-objectEncoder", new ObjectEncoder)
      pipeline.addLast("proxyServerToRemote-encrypt", new EncryptEncoder)
    }

    pipeline.addLast("proxyServerToRemote-httpResponseDecoder", new HttpResponseDecoder(DEFAULT_BUFFER_SIZE * 2, DEFAULT_BUFFER_SIZE * 4, DEFAULT_BUFFER_SIZE * 4))
    pipeline.addLast("proxyServerToRemote-httpRequestEncoder", httpRequestEncoder)
    //    pipeline.addLast("proxyServerToRemote-innerHttpChunkAggregator", new InnerHttpChunkAggregator())
    addIdleChannelHandler(pipeline)
    pipeline.addLast("proxyServerToRemote-proxyToServerHandler", new NetHttpResponseRelayingHandler(browserChannel))
  }
}

class WebProxyHttpRequestProcessor(request: HttpRequest, browserChannel: Channel)(implicit proxyConfig: ProxyConfig, connectHost: ConnectHost)
    extends HttpRequestProcessor(request, browserChannel) {

  require(connectHost.needForward, "The message must be need forward to remote WebProxy.")

  request.setUri(Utils.stripHost(request.getUri)) //We strip the absolute URL for general web proxy server.
  val proxyHost = Try(Host(httpRequest.getUri)).getOrElse(Host(httpRequest.getHeader(HttpHeaders.Names.HOST)))
  override val httpRequestEncoder = new WebProxyHttpRequestEncoder(connectHost, proxyHost, browserChannel)

  override def adjustPipelineForReused(channel: Channel) {
    channel.getPipeline.replace("proxyServerToRemote-httpRequestEncoder", "proxyServerToRemote-httpRequestEncoder", httpRequestEncoder)
    channel.getPipeline.replace(classOf[WebProxyResponseDecoder], "proxyServerToRemote-webProxyResponseDecoder", new WebProxyResponseDecoder(browserChannel))
    channel.getPipeline.replace(classOf[WebProxyHttpRelayingHandler], "proxyServerToRemote-proxyToServerHandler", new WebProxyHttpRelayingHandler(browserChannel))
  }

  override def proxyToServerPipeline = (pipeline: ChannelPipeline) ⇒ {
    if (connectHost.needForward && proxyConfig.proxyToServerSSLEnable) {
      val engine = proxyConfig.clientSSLContext.createSSLEngine
      engine.setUseClientMode(true)
      pipeline.addFirst("proxyServerToRemote-ssl", new SslHandler(engine))
    }

    pipeline.addLast("proxyServerToRemote-httpResponseDecoder", new HttpResponseDecoder(DEFAULT_BUFFER_SIZE * 2, DEFAULT_BUFFER_SIZE * 4, DEFAULT_BUFFER_SIZE * 4))
    pipeline.addLast("proxyServerToRemote-httpRequestEncoder", httpRequestEncoder)
    //    pipeline.addLast("proxyServerToRemote-innerHttpChunkAggregator", new InnerHttpChunkAggregator())
    addIdleChannelHandler(pipeline)
    pipeline.addLast("proxyServerToRemote-webProxyResponseDecoder", new WebProxyResponseDecoder(browserChannel))
    pipeline.addLast("proxyServerToRemote-webProxyHttpResponseDecoder", new HttpResponseDecoder(DEFAULT_BUFFER_SIZE * 2, DEFAULT_BUFFER_SIZE * 4, DEFAULT_BUFFER_SIZE * 4))
    pipeline.addLast("proxyServerToRemote-proxyToServerHandler", new WebProxyHttpRelayingHandler(browserChannel))
  }
}

abstract class HttpsRequestProcessor(request: HttpRequest, browserChannel: Channel)(implicit proxyConfig: ProxyConfig, connectHost: ConnectHost)
    extends RequestProcessor {
  require(request.getMethod == HttpMethod.CONNECT)
  browserChannel.setAttachment(UUID.randomUUID())
  override val httpRequest = request
}

class NetHttpsRequestProcessor(request: HttpRequest, browserChannel: Channel)(implicit proxyConfig: ProxyConfig, connectHost: ConnectHost)
    extends HttpsRequestProcessor(request, browserChannel) {

  val httpRequestEncoder = new HttpRequestEncoder()

  override def process {
    logger.info(s"Process request with $connectHost")
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

    if (connectHost.needForward && proxyConfig.proxyToServerSSLEnable) {
      val engine = proxyConfig.clientSSLContext.createSSLEngine
      engine.setUseClientMode(true)
      pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
    }

    if (connectHost.needForward) {
      pipeline.addLast("proxyServerToRemote-inflater", new IgnoreEmptyBufferZlibDecoder)
      pipeline.addLast("proxyServerToRemote-objectDecoder", new ObjectDecoder(ClassResolvers.weakCachingResolver(null)))
      pipeline.addLast("proxyServerToRemote-decrypt", new DecryptDecoder)

      pipeline.addLast("proxyServerToRemote-deflater", new IgnoreEmptyBufferZlibEncoder)
      pipeline.addLast("proxyServerToRemote-objectEncoder", new ObjectEncoder)
      pipeline.addLast("proxyServerToRemote-encrypt", new EncryptEncoder)
    }

    addIdleChannelHandler(pipeline)

    pipeline.addLast("proxyServerToRemote-connectionHandler", new NetHttpsRelayingHandler(browserChannel))
  }

  def connectComplete(future: ChannelFuture): Unit = {
    logger.info(s"[${future.getChannel}] - Connect successful")

    if (!future.isSuccess) {
      logger.info(s"Close browser connection: $browserChannel")
      Utils.closeChannel(browserChannel)
      return
    }

    val pipeline = browserChannel.getPipeline
    //Remove codec related handle for connect request, it's necessary for HTTPS.
    List("proxyServer-encoder", "proxyServer-decoder", "proxyServer-proxyHandler").foreach(pipeline remove _)
    pipeline.addLast("proxyServer-connectionHandler", new NetHttpsRelayingHandler(future.getChannel))
    def sendRequestToChainedProxy {
      future.getChannel.getPipeline.addBefore("proxyServerToRemote-connectionHandler", "proxyServerToRemote-encoder", httpRequestEncoder)
      future.getChannel.write(httpRequest).addListener {
        writeFuture: ChannelFuture ⇒
          writeFuture.getChannel.getPipeline.remove("proxyServerToRemote-encoder")
          logger.debug(s"[${future.getChannel}] - Finished write request: $httpRequest")
      }
    }

    if (connectHost.needForward)
      sendRequestToChainedProxy
    else {
      val connectResponse = ChannelBuffers.wrappedBuffer(Utils.connectProxyResponse.getBytes(UTF8))
      browserChannel.write(connectResponse).addListener {
        future: ChannelFuture ⇒ logger.info(s"[${future.getChannel}] - Finished write request: ${Utils.connectProxyResponse}")
      }
    }

    browserChannel.setReadable(true)
  }

}
class WebProxyHttpsRequestProcessor(request: HttpRequest, browserChannel: Channel)(implicit proxyConfig: ProxyConfig, connectHost: ConnectHost)
    extends HttpsRequestProcessor(request, browserChannel) {

  lazy val httpRequestEncoder = ???

  override def process {
    logger.info(s"Process request with $connectHost")
    val pipeline = browserChannel.getPipeline
    //Remove codec related handle for connect request, it's necessary for HTTPS.
    List("proxyServer-encoder", "proxyServer-decoder", "proxyServer-proxyHandler").foreach(pipeline remove _)
    pipeline.addLast("proxyServer-connectionHandler", new WebProxyHttpsRequestHandler(connectHost, Host(request.getUri)))
    val connectionMessage = ChannelBuffers.wrappedBuffer(HttpMethod.CONNECT.getName.getBytes(UTF8))
    Channels.fireMessageReceived(browserChannel, connectionMessage)
  }

}

class WebProxyHttpsRequestHandler(connectHost: ConnectHost, proxyHost: Host)(implicit proxyConfig: ProxyConfig)
    extends SimpleChannelUpstreamHandler with Logging {

  def createProxyToServerBootstrap(browserChannel: Channel) = {
    val proxyToServerBootstrap = newClientBootstrap
    proxyToServerBootstrap.setFactory(proxyConfig.clientSocketChannelFactory)
    proxyToServerBootstrap.setPipelineFactory {
      pipeline: ChannelPipeline ⇒
        if (proxyConfig.proxyToServerSSLEnable) {
          val engine = proxyConfig.clientSSLContext.createSSLEngine
          engine.setUseClientMode(true)
          pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
        }

        pipeline.addLast("proxyServerToRemote-decoder", new HttpResponseDecoder(DEFAULT_BUFFER_SIZE * 2, DEFAULT_BUFFER_SIZE * 4, DEFAULT_BUFFER_SIZE * 4))
        pipeline.addLast("proxyServerToRemote-encoder", new WebProxyHttpRequestEncoder(connectHost, proxyHost, browserChannel))
        addIdleChannelHandler(pipeline)
        pipeline.addLast("proxyServerToRemote-webProxyResponseDecoder", new WebProxyResponseDecoder(browserChannel))
        pipeline.addLast("proxyServerToRemote-connectionHandler", new WebProxyHttpsRelayingHandler(browserChannel))
    }
    proxyToServerBootstrap
  }

  override def messageReceived(channelContext: ChannelHandlerContext, e: MessageEvent) {
    val browserChannel = channelContext.getChannel
    logger.debug(s"$browserChannel Receive message:\n ${Utils.formatMessage(e.getMessage)}")

    val requestMessage = e.getMessage.asInstanceOf[ChannelBuffer]

    HttpsChannelManager.get(connectHost.host.socketAddress) match {
      case Some(channelFuture) if channelFuture.getChannel.isConnected ⇒ {
        val channel = channelFuture.getChannel
        logger.debug(s"$HttpsChannelManager")
        logger.info(s"Use existed channel ${channel}")

        //            channel.setAttachment(UUID.randomUUID())
        //            DefaultRequestManager.add(Request(channel.getAttachment.toString, browserChannel, channel))
        //            logger.error(s">>>>>>>>>>>>>>>>>[${browserChannel}] --[${channel.getAttachment}]--  [${channel}] - Connect successful.")

        channel.getPipeline.replace(classOf[WebProxyHttpRequestEncoder], "proxyServerToRemote-encoder", new WebProxyHttpRequestEncoder(connectHost, proxyHost, browserChannel))
        channel.getPipeline.replace(classOf[WebProxyResponseDecoder], "proxyServerToRemote-webProxyResponseDecoder", new WebProxyResponseDecoder(browserChannel))
        channel.getPipeline.replace(classOf[WebProxyHttpsRelayingHandler], "proxyServerToRemote-connectionHandler", new WebProxyHttpsRelayingHandler(browserChannel))
        channel.write(requestMessage).addListener {
          writeFuture: ChannelFuture ⇒ logger.debug(s"[${channel}] - Finished write request: ${Utils.formatMessage(requestMessage)}")
        }
      }
      case _ ⇒ createConnectionAndWriteRequest
    }

    def createConnectionAndWriteRequest = {
      browserChannel.setReadable(false)
      createProxyToServerBootstrap(browserChannel).connect(connectHost.host.socketAddress).addListener(connectComplete _)
    }

    def connectComplete(future: ChannelFuture): Unit = {
      logger.info(s"[${future.getChannel}] - Connect successful.")
      browserChannel.setReadable(true)
      if (!future.isSuccess) {
        logger.info(s"Close browser connection: $browserChannel")
        Utils.closeChannel(browserChannel)
        return
      }

      //      future.getChannel.setAttachment(UUID.randomUUID())
      //      DefaultRequestManager.add(Request(future.getChannel.getAttachment.toString, browserChannel, future.getChannel))
      //      logger.error(s">>>>>>>>>>>>>>>>>[${browserChannel}] --[${browserChannel.getAttachment}]--  [${future.getChannel}] - Connect successful.")

      future.getChannel.write(requestMessage).addListener { writeFuture: ChannelFuture ⇒
        logger.debug(s"[${future.getChannel}] - Finished write request: ${Utils.formatMessage(requestMessage)}")
      }
    }
  }

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

