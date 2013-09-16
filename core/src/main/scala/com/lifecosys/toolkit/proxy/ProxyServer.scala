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

import java.net.InetSocketAddress
import org.jboss.netty.channel._
import group.ChannelGroupFuture
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.bootstrap.ServerBootstrap
import java.util.concurrent.atomic.{ AtomicInteger, AtomicBoolean }
import scala.collection.JavaConversions._
import org.jboss.netty.handler.ssl.SslHandler
import org.jboss.netty.handler.codec.serialization.{ ClassResolvers, ObjectEncoder, ObjectDecoder }
import com.typesafe.scalalogging.slf4j.Logging
import java.nio.channels.ClosedChannelException
import java.util.UUID
import org.jboss.netty.handler.stream.ChunkedWriteHandler
import org.jboss.netty.handler.logging.LoggingHandler
import org.jboss.netty.logging.InternalLogLevel

/**
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 12/2/12 1:44 AM
 */

object ProxyServer {

  def initialize = {

  }

  def apply(config: ProxyConfig) = new ProxyServer(config)

}

class NettyWebProxyServer(proxyConfig: ProxyConfig) extends ProxyServer(proxyConfig) {
  override def proxyServerPipeline = (pipeline: ChannelPipeline) ⇒ {

    pipeline.addLast("logger", new LoggingHandler(InternalLogLevel.ERROR, true))
    if (proxyConfig.serverSSLEnable) {
      val engine = proxyConfig.serverSSLContext.createSSLEngine()
      engine.setUseClientMode(false)
      engine.setNeedClientAuth(true)
      pipeline.addLast("proxyServer-ssl", new SslHandler(engine))
    }

    pipeline.addLast("proxyServer-decoder", new HttpRequestDecoder(DEFAULT_BUFFER_SIZE * 2, DEFAULT_BUFFER_SIZE * 4, DEFAULT_BUFFER_SIZE * 4))
    pipeline.addLast("proxyServer-WebProxyHttpRequestBufferDecoder", new WebProxyHttpRequestDecoder)
    pipeline.addLast("proxyServer-WebProxyHttpRequestDecoder", new HttpRequestDecoder(DEFAULT_BUFFER_SIZE * 2, DEFAULT_BUFFER_SIZE * 4, DEFAULT_BUFFER_SIZE * 4))
    //      pipeline.addLast("aggregator", new ChunkAggregator(65536))
    pipeline.addLast("proxyServer-webProxyResponseEncoder", new HttpResponseEncoder())
    pipeline.addLast("proxyServer-webProxyResponseBufferEncoder", new WebProxyResponseBufferEncoder())
    pipeline.addLast("proxyServer-responseEncoder", new HttpResponseEncoder())
    addIdleChannelHandler(pipeline)
    pipeline.addLast("proxyServer-proxyHandler", new NettyWebProxyRequestHandler)
  }
}

/**
 * Don't user this construct to create a proxy server, you should use the companion object <code>ProxyServer</code>
 * Since some environment need to be initialize, such as security provider.
 *
 * @param proxyConfig
 */
class ProxyServer(proxyConfig: ProxyConfig) extends Logging {
  require(proxyConfig != null)

  implicit val currentProxyConfig = proxyConfig

  val isStarted: AtomicBoolean = new AtomicBoolean(false)

  val serverBootstrap = new ServerBootstrap(proxyConfig.serverSocketChannelFactory)

  def proxyServerPipeline = (pipeline: ChannelPipeline) ⇒ {
    pipeline.addLast("logger", new LoggingHandler(InternalLogLevel.ERROR, true))
    if (proxyConfig.serverSSLEnable) {
      val engine = proxyConfig.serverSSLContext.createSSLEngine()
      engine.setUseClientMode(false)
      engine.setNeedClientAuth(true)
      pipeline.addLast("proxyServer-ssl", new SslHandler(engine))
    }

    if (!proxyConfig.isLocal) {
      pipeline.addLast("proxyServer-inflater", new IgnoreEmptyBufferZlibDecoder)
      pipeline.addLast("proxyServer-objectDecoder", new ObjectDecoder(ClassResolvers.weakCachingResolver(null)))
      pipeline.addLast("proxyServer-decrypt", new DecryptDecoder)

      pipeline.addLast("proxyServer-deflater", new IgnoreEmptyBufferZlibEncoder)
      pipeline.addLast("proxyServer-objectEncoder", new ObjectEncoder)
      pipeline.addLast("proxyServer-encrypt", new EncryptEncoder)
    }

    pipeline.addLast("proxyServer-decoder", new HttpRequestDecoder(DEFAULT_BUFFER_SIZE * 2, DEFAULT_BUFFER_SIZE * 4, DEFAULT_BUFFER_SIZE * 4))
    //      pipeline.addLast("aggregator", new ChunkAggregator(65536))
    pipeline.addLast("proxyServer-encoder", new HttpResponseEncoder())
    addIdleChannelHandler(pipeline)
    pipeline.addLast("proxyServer-proxyHandler", new ProxyRequestHandler)
  }

  def shutdown = {
    logger.info("Shutting down proxy")
    isStarted.get match {
      case true ⇒ shutdown
      case _    ⇒ logger.info("Already stopped")
    }
    def shutdown {
      isStarted.set(false)

      logger.info("Closing all channels...")
      val future: ChannelGroupFuture = proxyConfig.allChannels.close
      future.awaitUninterruptibly(10 * 1000)

      if (!future.isCompleteSuccess) {
        future.iterator().filterNot(_.isSuccess).foreach {
          channelFuture ⇒ logger.warn("Can't close %s, case by %s".format(channelFuture.getChannel, channelFuture.getCause))
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
    //    serverBootstrap.setOption("tcpNoDelay", true)
    //    serverBootstrap.setOption("keepAlive", true)
    serverBootstrap.setOption("connectTimeoutMillis", 120 * 1000)
    //    serverBootstrap.setOption("child.keepAlive", true)
    serverBootstrap.setOption("child.connectTimeoutMillis", 120 * 1000)

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

class NettyWebProxyRequestHandler(implicit proxyConfig: ProxyConfig) extends ProxyRequestHandler {
  override def requestProcessor(httpRequest: HttpRequest, ctx: ChannelHandlerContext): RequestProcessor = {
    implicit val connectHost = proxyConfig.getChainProxyManager.getConnectHost(httpRequest.getUri).get
    if (HttpMethod.CONNECT == httpRequest.getMethod)
      new NettyWebProxyHttpsRequestProcessor(httpRequest, ctx.getChannel)
    else
      new NettyWebProxyHttpRequestProcessor(httpRequest, ctx.getChannel)
  }
}
class ProxyRequestHandler(implicit proxyConfig: ProxyConfig)
    extends SimpleChannelUpstreamHandler with Logging {

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.debug(s"[${e.getChannel}] - Receive message\n${Utils.formatMessage(e.getMessage)}")

    require(e.getMessage.isInstanceOf[HttpRequest], "Unsupported Request..........")
    val httpRequest = e.getMessage.asInstanceOf[HttpRequest]
    //
    //    synchronized {
    //      val index = Option(ctx.getChannel.getAttachment).getOrElse(new AtomicInteger).asInstanceOf[AtomicInteger]
    //      index.incrementAndGet()
    //      ctx.getChannel.setAttachment(index)
    //    }
    //    logger.error(s">>>>>>>>>>>>>>>>>setAttachment completed ${ctx.getChannel.getAttachment}  on setAttachment${ctx.getChannel}")

    //    try {
    //      require(!requestsssss.contains(ctx.getChannel.toString))
    //    } catch {
    //      case t ⇒ logger.error(s">>>>>>>>>>>>>>>>>${ctx.getChannel.toString}Duplicated request. ")
    //    }
    //    synchronized(requestsssss += ctx.getChannel.toString)
    //    if (httpRequest.getUri.contains("safebrowsing") || httpRequest.getUri.contains(".mozilla.com")) {
    //      ctx.getChannel.close()
    //      return
    //    }

    requestProcessor(httpRequest, ctx) process

  }

  def requestProcessor(httpRequest: HttpRequest, ctx: ChannelHandlerContext): RequestProcessor = {
    implicit val connectHost = proxyConfig.getChainProxyManager.getConnectHost(httpRequest.getUri).get
    val requestProcessor = connectHost.serverType match {
      case WebProxyType if HttpMethod.CONNECT == httpRequest.getMethod ⇒ new WebProxyHttpsRequestProcessor(httpRequest, ctx.getChannel)
      case WebProxyType ⇒ new WebProxyHttpRequestProcessor(httpRequest, ctx.getChannel)
      case NettyWebProxyType if HttpMethod.CONNECT == httpRequest.getMethod ⇒ new NettyWebProxyClientHttpsRequestProcessor(httpRequest, ctx.getChannel)
      case NettyWebProxyType ⇒ new NettyWebProxyClientHttpRequestProcessor(httpRequest, ctx.getChannel)
      case other if HttpMethod.CONNECT == httpRequest.getMethod ⇒ new NetHttpsRequestProcessor(httpRequest, ctx.getChannel)
      case other ⇒ new DefaultHttpRequestProcessor(httpRequest, ctx.getChannel)
    }
    requestProcessor
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

