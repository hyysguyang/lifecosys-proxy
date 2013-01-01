package com.lifecosys.toolkit.proxy

import org.jboss.netty.handler.codec.http.{HttpRequestEncoder, HttpClientCodec, HttpRequest}
import org.jboss.netty.channel.{ChannelPipeline, ChannelFuture, ChannelHandlerContext}
import org.jboss.netty.bootstrap.ClientBootstrap
import java.net.InetSocketAddress
import org.jboss.netty.handler.ssl.SslHandler
import org.jboss.netty.handler.timeout.{IdleStateEvent, IdleStateAwareChannelHandler, IdleStateHandler}
import org.jboss.netty.buffer.ChannelBuffers
import com.lifecosys.toolkit.proxy.ProxyServer._

/**
 * 
 *
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 1/1/13 5:50 PM
 */

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
    import scala.collection.JavaConverters._
    pipeline.getNames.asScala.filterNot(List("logger", "ssl").contains(_)).foreach(pipeline remove _)

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

