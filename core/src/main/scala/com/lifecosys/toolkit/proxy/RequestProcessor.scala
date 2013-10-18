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
import org.jboss.netty.buffer.ChannelBuffers
import org.jboss.netty.handler.codec.serialization.{ ClassResolvers, ObjectDecoder, ObjectEncoder }
import com.typesafe.scalalogging.slf4j.Logging
import scala.util.Try
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

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

  preProcess
  //
  //  synchronized {
  //    val index = Option(browserChannel.getAttachment).getOrElse().asInstanceOf[AtomicInteger]
  //    Option(ctx.getChannel.getAttachment).getOrElse(new AtomicInteger).asInstanceOf[AtomicInteger].incrementAndGet()
  //    ctx.getChannel.setAttachment(index)
  //  }

  //Can't play online video since we send the full url for http request,
  // Exactly, we need use the relative url to access the remote server.
  if (!connectHost.needForward) httpRequest.setUri(Utils.stripHost(httpRequest.getUri))

  def preProcess = {
    Option(browserChannel.getAttachment) match {
      case Some(requestCounter) ⇒ {
        require(requestCounter.isInstanceOf[AtomicInteger], "Request counter must be integer")
        requestCounter.asInstanceOf[AtomicInteger].incrementAndGet()
      }
      case None ⇒ browserChannel.setAttachment(new AtomicInteger(1))
    }
  }
  def process = {
    logger.info(s"Process request with $connectHost")
    HttpChannelManager.get(connectHost.host.socketAddress) match {
      case Some(channelFuture) if channelFuture.getChannel.isConnected ⇒ {
        val channel = channelFuture.getChannel
        logger.debug(s"$HttpChannelManager")
        logger.info(s"Use existed channel ${channel}")

        channel.setAttachment(UUID.randomUUID())
        //        DefaultRequestManager.add(Request(channel.getAttachment.toString, browserChannel, channel))

        adjustPipelineForReused(channel)
        channel.write(httpRequest).addListener {
          writeFuture: ChannelFuture ⇒ logger.debug(s"[${channel}] - Finished write request: ${Utils.formatMessage(httpRequest)}")
        }
      }
      case _ ⇒ createConnectionAndWriteRequest
    }
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
    channel.getPipeline.replace("proxyServerToRemote-proxyToServerHandler", "proxyServerToRemote-proxyToServerHandler", new NettyWebProxyServerHttpRelayingHandler(browserChannel))
  }

  override def preProcess = {
  }

  override def process = {
    logger.info(s"Process request with $connectHost")
    HttpChannelManager.get(connectHost.host.socketAddress) match {
      case Some(channelFuture) if channelFuture.getChannel.isConnected ⇒ {
        val channel = channelFuture.getChannel
        logger.debug(s"$HttpChannelManager")
        logger.info(s"Use existed channel ${channel}")

        channel.setAttachment(UUID.randomUUID())
        //        DefaultRequestManager.add(Request(channel.getAttachment.toString, browserChannel, channel))

        adjustPipelineForReused(channel)

        httpRequest.setUri(Utils.stripHost(httpRequest.getUri))

        channel.write(httpRequest).addListener {
          writeFuture: ChannelFuture ⇒ logger.debug(s"[${channel}] - Finished write request: ${Utils.formatMessage(httpRequest)}")
        }
      }
      case _ ⇒ createConnectionAndWriteRequest
    }
  }

  override def writeRequest(future: ChannelFuture) {

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
    pipeline.addLast("proxyServerToRemote-httpResponseDecoder", new HttpResponseDecoder(DEFAULT_BUFFER_SIZE * 2, DEFAULT_BUFFER_SIZE * 4, DEFAULT_BUFFER_SIZE * 4))
    pipeline.addLast("proxyServerToRemote-httpRequestEncoder", new HttpRequestEncoder())
    pipeline.addLast("proxyServerToRemote-proxyToServerHandler", new NettyWebProxyServerHttpRelayingHandler(browserChannel))

  }
}

class NettyWebProxyClientHttpRequestProcessor(request: HttpRequest, browserChannel: Channel)(implicit proxyConfig: ProxyConfig, connectHost: ConnectHost)
    extends HttpRequestProcessor(request, browserChannel) {
  val proxyHost = Try(Host(httpRequest.getUri)).getOrElse(Host(httpRequest.getHeader(HttpHeaders.Names.HOST)))
  val httpRequestEncoder = new NettyWebProxyHttpRequestEncoder(connectHost, proxyHost, browserChannel)

  override def adjustPipelineForReused(channel: Channel) {
    channel.getPipeline.replace("proxyServerToRemote-webProxyEncryptDataDecoder", "proxyServerToRemote-webProxyEncryptDataDecoder", new EncryptDataFrameDecoder)
    channel.getPipeline.replace("proxyServerToRemote-webProxyResponseBufferDecoder", "proxyServerToRemote-webProxyResponseBufferDecoder", new WebProxyResponseDecoder(browserChannel))
    channel.getPipeline.replace("proxyServerToRemote-httpRequestEncoder", "proxyServerToRemote-httpRequestEncoder", new NettyWebProxyHttpRequestEncoder(connectHost, proxyHost, browserChannel))
    channel.getPipeline.replace("proxyServerToRemote-proxyToServerHandler", "proxyServerToRemote-proxyToServerHandler", new NettyWebProxyClientHttpRelayingHandler(browserChannel))
  }

  override def proxyToServerPipeline = (pipeline: ChannelPipeline) ⇒ {
    //    pipeline.addLast("logger", new LoggingHandler(InternalLogLevel.ERROR, true))
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

    pipeline.addLast("proxyServerToRemote-httpResponseDecoder", new HttpResponseDecoder(DEFAULT_BUFFER_SIZE * 2, DEFAULT_BUFFER_SIZE * 4, DEFAULT_BUFFER_SIZE * 4))
    pipeline.addLast("proxyServerToRemote-webProxyResponseBufferDecoder", new WebProxyResponseDecoder(browserChannel))
    pipeline.addLast("proxyServerToRemote-webProxyEncryptDataDecoder", new EncryptDataFrameDecoder)
    pipeline.addLast("proxyServerToRemote-webProxyResponseDecoder", new HttpResponseDecoder(DEFAULT_BUFFER_SIZE * 2, DEFAULT_BUFFER_SIZE * 4, DEFAULT_BUFFER_SIZE * 4))

    pipeline.addLast("proxyServerToRemote-httpRequestEncoder", new NettyWebProxyHttpRequestEncoder(connectHost, proxyHost, browserChannel))

    addIdleChannelHandler(pipeline)

    pipeline.addLast("proxyServerToRemote-proxyToServerHandler", new NettyWebProxyClientHttpRelayingHandler(browserChannel))
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

abstract class HttpsRequestProcessor(request: HttpRequest, browserChannel: Channel)(implicit proxyConfig: ProxyConfig, connectHost: ConnectHost)
    extends RequestProcessor {
  require(request.getMethod == HttpMethod.CONNECT)

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
    //    pipeline.addLast("logger", new LoggingHandler(InternalLogLevel.ERROR, true))

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

class NettyWebProxyHttpsRequestProcessor(request: HttpRequest, browserChannel: Channel)(implicit proxyConfig: ProxyConfig, connectHost: ConnectHost)
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

    pipeline.addLast("proxyServerToRemote-connectionHandler", new NettyWebProxyServerHttpsRelayingHandler(browserChannel))
  }

  def connectComplete(future: ChannelFuture): Unit = {
    logger.info(s"[${future.getChannel}] - Connect successful")

    if (!future.isSuccess) {
      logger.info(s"Close browser connection: $browserChannel")
      Utils.closeChannel(browserChannel)
      return
    }

    DefaultHttpsRequestManager.add(browserChannel.getAttachment.toString, future.getChannel)
    //    logger.error(s"#####################################################\n$DefaultHttpsRequestManager#####################################################")

    httpRequest.setUri(Utils.stripHost(httpRequest.getUri))

    val pipeline = browserChannel.getPipeline
    //Remove codec related handle for connect request, it's necessary for HTTPS.
    //    List("proxyServer-WebProxyHttpRequestDecoder", "proxyServer-proxyHandler").foreach(pipeline remove _)
    //    pipeline.addLast("proxyServer-connectionHandler", new NetHttpsRelayingHandler(future.getChannel))
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

class NettyWebProxyClientHttpsRequestProcessor(request: HttpRequest, browserChannel: Channel)(implicit proxyConfig: ProxyConfig, connectHost: ConnectHost)
    extends HttpsRequestProcessor(request, browserChannel) {
  browserChannel.setAttachment(UUID.randomUUID())
  val proxyHost = Try(Host(httpRequest.getUri)).getOrElse(Host(httpRequest.getHeader(HttpHeaders.Names.HOST)))
  val httpRequestEncoder = new NettyWebProxyHttpRequestEncoder(connectHost, proxyHost, browserChannel)

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

    //    pipeline.addLast("logger", new LoggingHandler(InternalLogLevel.ERROR, true))

    if (connectHost.needForward && proxyConfig.proxyToServerSSLEnable) {
      val engine = proxyConfig.clientSSLContext.createSSLEngine
      engine.setUseClientMode(true)
      pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
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

    pipeline.addLast("proxyServerToRemote-httpResponseDecoder", new HttpResponseDecoder(DEFAULT_BUFFER_SIZE * 2, DEFAULT_BUFFER_SIZE * 4, DEFAULT_BUFFER_SIZE * 4))
    pipeline.addLast("proxyServerToRemote-webProxyResponseBufferDecoder", new WebProxyResponseDecoder(browserChannel))
    pipeline.addLast("proxyServerToRemote-webProxyEncryptDataDecoder", new EncryptDataFrameDecoder)
    pipeline.addLast("proxyServerToRemote-encoder", httpRequestEncoder)
    addIdleChannelHandler(pipeline)

    pipeline.addLast("proxyServerToRemote-connectionHandler", new NettyWebProxyClientHttpsRelayingHandler(browserChannel))
  }

  def connectComplete(future: ChannelFuture): Unit = {
    logger.info(s"[${future.getChannel}] - Connect successful")

    if (!future.isSuccess) {
      logger.info(s"Close browser connection: $browserChannel")
      Utils.closeChannel(browserChannel)
      return
    }

    //    val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
    //    response.setChunked(true)
    //    browserChannel.write(response)
    //
    httpRequest.setUri(Utils.stripHost(httpRequest.getUri))

    future.getChannel.setAttachment(UUID.randomUUID())

    val pipeline = browserChannel.getPipeline
    //Remove codec related handle for connect request, it's necessary for HTTPS.
    List("proxyServer-decoder", "proxyServer-encoder", "proxyServer-proxyHandler").foreach(pipeline remove _)
    pipeline.addLast("proxyServer-connectionHandler", new NetHttpsRelayingHandler(future.getChannel))
    def sendRequestToChainedProxy {
      future.getChannel.write(request).addListener {
        writeFuture: ChannelFuture ⇒
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

