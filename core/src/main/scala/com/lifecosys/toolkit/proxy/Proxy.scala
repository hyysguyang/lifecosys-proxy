/*
 * ===Begin Copyright Notice===
 *
 * NOTICE
 *
 * THIS SOFTWARE IS THE PROPERTY OF AND CONTAINS CONFIDENTIAL INFORMATION OF
 * LIFECOSYS AND/OR ITS AFFILIATES OR SUBSIDIARIES AND SHALL NOT BE DISCLOSED
 * WITHOUT PRIOR WRITTEN PERMISSION. LICENSED CUSTOMERS MAY COPY AND ADAPT
 * THIS SOFTWARE FOR THEIR OWN USE IN ACCORDANCE WITH THE TERMS OF THEIR
 * SOFTWARE LICENSE AGREEMENT. ALL OTHER RIGHTS RESERVED.
 *
 * (c) COPYRIGHT 2013 LIFECOCYS. ALL RIGHTS RESERVED. THE WORD AND DESIGN
 * MARKS SET FORTH HEREIN ARE TRADEMARKS AND/OR REGISTERED TRADEMARKS OF
 * LIFECOSYS AND/OR ITS AFFILIATES AND SUBSIDIARIES. ALL RIGHTS RESERVED.
 * ALL LIFECOSYS TRADEMARKS LISTED HEREIN ARE THE PROPERTY OF THEIR RESPECTIVE
 * OWNERS.
 *
 * ===End Copyright Notice===
 */

package com.lifecosys.toolkit.proxy

import org.jboss.netty.logging.{Slf4JLoggerFactory, InternalLoggerFactory}
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import org.jboss.netty.channel._
import collection.mutable
import group.ChannelGroupFuture
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.bootstrap.{ClientBootstrap, ServerBootstrap}
import org.jboss.netty.buffer.{ChannelBuffer, ChannelBuffers}
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConversions._
import org.jboss.netty.handler.ssl.SslHandler
import scala.Some
import org.jboss.netty.handler.timeout.{IdleStateEvent, IdleStateAwareChannelHandler, IdleStateHandler}
import org.jboss.netty.util.HashedWheelTimer
import com.lifecosys.toolkit.proxy.ProxyServer._


/**
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 12/2/12 1:44 AM
 */
object ProxyServer {

  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  val logger = LoggerFactory.getLogger(getClass)
  val timer = new HashedWheelTimer

  val hostToChannelFuture = mutable.Map[InetSocketAddress, Channel]()

  implicit def channelPipelineInitializer(f: ChannelPipeline => Unit): ChannelPipelineFactory = new ChannelPipelineFactory {
    def getPipeline: ChannelPipeline = {
      val pipeline: ChannelPipeline = Channels.pipeline()
      f(pipeline)
      pipeline
    }
  }

  implicit def channelFutureListener(f: ChannelFuture => Unit): ChannelFutureListener = new ChannelFutureListener {
    def operationComplete(future: ChannelFuture) = f(future)
  }


  def apply(config: ProxyConfig) = new ProxyServer(config)

}


class ProxyServer(val proxyConfig: ProxyConfig = new SimpleProxyConfig) {
  implicit val currentProxyConfig = proxyConfig

  val isStarted: AtomicBoolean = new AtomicBoolean(false)

  val serverBootstrap = new ServerBootstrap(proxyConfig.serverSocketChannelFactory)

  def proxyServerPipeline = (pipeline: ChannelPipeline) => {
    //      pipeline.addLast("logger", new LoggingHandler(proxyConfig.loggerLevel,false))
    if (proxyConfig.serverSSLEnable) {
      val engine = proxyConfig.serverSSLContext.createSSLEngine()
      engine.setUseClientMode(false)
      engine.setNeedClientAuth(true)
      pipeline.addLast("ssl", new SslHandler(engine))
    }

    pipeline.addLast("decoder", new HttpRequestDecoder(8192 * 2, 8192 * 4, 8192 * 4))
    //      pipeline.addLast("aggregator", new ChunkAggregator(65536))
    pipeline.addLast("encoder", new HttpResponseEncoder())
    pipeline.addLast("idle", new IdleStateHandler(timer, 0, 0, 120))
    pipeline.addLast("idleAware", new IdleStateAwareChannelHandler {
      override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
        logger.debug("Channel idle........{}", e.getChannel)
        Utils.closeChannel(e.getChannel)
      }
    })
    pipeline.addLast("proxyHandler", new ProxyHandler)
  }

  def shutdown = {
    logger.info("Shutting down proxy")
    isStarted.get match {
      case true => shutdown
      case _ => logger.info("Already stopped")
    }
    def shutdown {
      isStarted.set(false)

      logger.info("Closing all channels...")
      val future: ChannelGroupFuture = proxyConfig.allChannels.close
      future.awaitUninterruptibly(10 * 1000)

      if (!future.isCompleteSuccess) {
        future.iterator().filterNot(_.isSuccess).foreach {
          channelFuture => logger.warn("Can't close {}, case by {}", Array(channelFuture.getChannel, channelFuture.getCause))
        }

        proxyConfig.serverSocketChannelFactory.releaseExternalResources()
        proxyConfig.clientSocketChannelFactory.releaseExternalResources()
        logger.info("Done shutting down proxy")
      }
    }
  }

  def start = {
    logger.info("Starting proxy server on " + proxyConfig.port)
    serverBootstrap.setPipelineFactory(proxyServerPipeline)
    serverBootstrap.setOption("tcpNoDelay", true)
    serverBootstrap.setOption("keepAlive", true)
    serverBootstrap.setOption("connectTimeoutMillis", 60 * 1000)
    serverBootstrap.setOption("child.keepAlive", true)
    serverBootstrap.setOption("child.connectTimeoutMillis", 60 * 1000)

    proxyConfig.allChannels.add(serverBootstrap.bind(new InetSocketAddress(proxyConfig.port)))

    Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
      def run {
        logger.info("Shutdown proxy server now.............")
        shutdown
      }
    }))

    isStarted.set(true)

    logger.info("Proxy server started on " + proxyConfig.port)
  }
}

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


trait RequestProcessor {
  def process

  val httpRequest: HttpRequest
  val browserToProxyContext: ChannelHandlerContext

  def newClientBootstrap = {
    val proxyToServerBootstrap = new ClientBootstrap()
    proxyToServerBootstrap.setOption("keepAlive", true)
    proxyToServerBootstrap.setOption("connectTimeoutMillis", 60 * 1000)
    proxyToServerBootstrap
  }
}


class DefaultRequestProcessor(request: HttpRequest, browserToProxyChannelContext: ChannelHandlerContext)(implicit proxyConfig: ProxyConfig) extends RequestProcessor {

  override val httpRequest: HttpRequest = request
  override val browserToProxyContext = browserToProxyChannelContext

  val chainedProxy = proxyConfig.chainProxies.headOption
  val host: InetSocketAddress = chainedProxy.getOrElse(Utils.parseHostAndPort(httpRequest.getUri))
  val browserToProxyChannel = browserToProxyContext.getChannel

  //Can't play online video since we send the full url for http request,
  // Exactly, we need use the relative url to access the remote server.
  if (chainedProxy.isEmpty) httpRequest.setUri(Utils.stripHost(httpRequest.getUri))

  def process {
    hostToChannelFuture.get(host) match {
      case Some(channel) if channel.isConnected => {
        logger.error("###########Use existed Proxy toserver conntection: {}################## Size {}##################", channel, hostToChannelFuture.size)
        channel.write(httpRequest)
      }
      case _ => {
        browserToProxyChannel.setReadable(false)
        val proxyToServerBootstrap = newClientBootstrap
        proxyToServerBootstrap.setFactory(proxyConfig.clientSocketChannelFactory)
        proxyToServerBootstrap.setPipelineFactory(proxyToServerPipeline)
        proxyToServerBootstrap.connect(host).addListener(connectProcess _)
      }
    }
  }

  def connectProcess(future: ChannelFuture) {
    future.isSuccess match {
      case true => {
        //                  hostToChannelFuture.put(host, future.getChannel)
        future.getChannel().write(httpRequest).addListener {
          future: ChannelFuture => if (logger.isDebugEnabled()) logger.debug("Write request to remote server {} completed.", future.getChannel)
        }
        browserToProxyChannel.setReadable(true)
      }
      case false => {
        if (logger.isDebugEnabled()) logger.debug("Close browser connection...")
        browserToProxyChannel.setReadable(true)
        Utils.closeChannel(browserToProxyChannel)
      }
    }
  }


  def proxyToServerPipeline = (pipeline: ChannelPipeline) => {
    //pipeline.addLast("logger", new LoggingHandler(proxyConfig.loggerLevel))
    if (proxyConfig.proxyToServerSSLEnable) {
      val engine = proxyConfig.clientSSLContext.createSSLEngine
      engine.setUseClientMode(true)
      pipeline.addLast("ssl", new SslHandler(engine))
    }
    pipeline.addLast("codec", new HttpClientCodec(8192 * 2, 8192 * 4, 8192 * 4))
    // Remove the following line if you don't want automatic content decompression.
    //NOTE: Don't add inflater handler which result image can't be load...
    //      pipeline.addLast("inflater", new HttpContentDecompressor)
    // Uncomment the following line if you don't want to handle HttpChunks.
    pipeline.addLast("idle", new IdleStateHandler(timer, 0, 0, 120))
    pipeline.addLast("idleAware", new IdleStateAwareChannelHandler {
      override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
        logger.debug("Channel idle........{}", e.getChannel)
        Utils.closeChannel(e.getChannel)
      }
    })
    pipeline.addLast("proxyToServerHandler", new HttpRelayingHandler(browserToProxyChannel))
  }

}

class ConnectionRequestProcessor(request: HttpRequest, browserToProxyChannelContext: ChannelHandlerContext)(implicit proxyConfig: ProxyConfig) extends RequestProcessor {
  override val httpRequest: HttpRequest = request
  val browserToProxyContext = browserToProxyChannelContext

  val chainedProxy = proxyConfig.chainProxies.headOption
  val host: InetSocketAddress = chainedProxy.getOrElse(Utils.parseHostAndPort(httpRequest.getUri))
  val browserToProxyChannel = browserToProxyContext.getChannel

  def process {
    hostToChannelFuture.get(host) match {
      case Some(channel) if channel.isConnected => browserToProxyChannel.write(ChannelBuffers.copiedBuffer(Utils.connectProxyResponse.getBytes("UTF-8")))
      case None => {
        browserToProxyChannel.setReadable(false)
        if (logger.isDebugEnabled()) logger.debug("Starting new connection to: {}", host)
        createProxyToServerBootstrap.connect(host).addListener(connectComplete _)
      }
    }
  }


  def createProxyToServerBootstrap = {
    val proxyToServerBootstrap = newClientBootstrap
    proxyToServerBootstrap.setFactory(proxyConfig.clientSocketChannelFactory)
    proxyToServerBootstrap.setPipelineFactory {
      pipeline: ChannelPipeline =>

      //pipeline.addLast("logger", new LoggingHandler(proxyConfig.loggerLevel))

        if (proxyConfig.proxyToServerSSLEnable) {
          val engine = proxyConfig.clientSSLContext.createSSLEngine
          engine.setUseClientMode(true)
          pipeline.addLast("ssl", new SslHandler(engine))
        }
        pipeline.addLast("connectionHandler", new ConnectionRequestHandler(browserToProxyChannel))
    }
    proxyToServerBootstrap
  }


  def connectComplete(future: ChannelFuture): Unit = {
    if (logger.isDebugEnabled()) logger.debug("Connection successful: {}", future.getChannel)

    if (!future.isSuccess) {
      if (logger.isDebugEnabled()) logger.debug("Close browser connection...")
      Utils.closeChannel(browserToProxyChannel)
      return
    }

    val pipeline = browserToProxyChannel.getPipeline
    pipeline.getNames.filterNot(List("logger", "ssl").contains(_)).foreach(pipeline remove _)

    pipeline.addLast("connectionHandler", new ConnectionRequestHandler(future.getChannel))
    chainedProxy match {
      case Some(chainedProxyServer) => {
        future.getChannel.getPipeline.addBefore("connectionHandler", "encoder", new HttpRequestEncoder)
        future.getChannel.write(httpRequest).addListener {
          future: ChannelFuture => {
            future.getChannel.getPipeline.remove("encoder")
            if (logger.isDebugEnabled()) logger.debug("Finished write request to {} \n {} ", Array(future.getChannel, httpRequest))
          }
        }
      }
      case None => browserToProxyChannel.write(ChannelBuffers.copiedBuffer(Utils.connectProxyResponse.getBytes("UTF-8"))).addListener {
        future: ChannelFuture => if (logger.isDebugEnabled()) logger.debug("Finished write request to {} \n {} ", Array(future.getChannel, Utils.connectProxyResponse))
      }
    }

    browserToProxyChannel.setReadable(true)
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
