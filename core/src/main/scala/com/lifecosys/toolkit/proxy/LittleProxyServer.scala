package com.lifecosys.toolkit.proxy

import org.littleshoot.proxy._
import org.jboss.netty.channel.{ ChannelPipeline, ChannelPipelineFactory }
import org.jboss.netty.handler.codec.http.HttpRequest
import org.jboss.netty.handler.ssl.SslHandler
import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.channel.socket.ClientSocketChannelFactory
import org.jboss.netty.util.Timer
import org.littleshoot.proxy.{ ChainProxyManager ⇒ LittleChainProxyManager }
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel.group.ChannelGroup

/**
 *
 *
 * @author Young Gu
 * @version 1.0 6/6/13 3:46 PM
 */
class LittleProxyServer(port: Int)(implicit proxyConfig: ProxyConfig) extends org.littleshoot.proxy.DefaultHttpProxyServer(port) {
  val chainProxyManager = proxyConfig.getChainProxyManager
  val serverEngine = if (!proxyConfig.serverSSLEnable) null else {
    val engine = proxyConfig.serverSSLContext.createSSLEngine
    engine.setUseClientMode(false)
    engine.setNeedClientAuth(true)
    engine
  }

  val clientEngine = if (!proxyConfig.proxyToServerSSLEnable) null else {
    val engine = proxyConfig.clientSSLContext.createSSLEngine
    engine.setUseClientMode(true)
    engine
  }

  val littleChainProxyManager = if (proxyConfig.chainProxies.isEmpty) null else new LittleChainProxyManager() {
    def getChainProxy(request: HttpRequest): String = {
      val proxyHost = chainProxyManager.getConnectHost(request.getUri)
      if (!proxyHost.needForward) null else proxyHost.host.getHostString + ":" + proxyHost.host.getPort
    }

    def onCommunicationError(hostAndPort: String) {}
  }

  override protected def preBind(serverBootstrap: ServerBootstrap,
                                 allChannels: ChannelGroup,
                                 clientChannelFactory: ClientSocketChannelFactory,
                                 timer: Timer,
                                 authenticationManager: ProxyAuthorizationManager,
                                 responseFilters: HttpResponseFilters,
                                 requestFilter: HttpRequestFilter) {
    val relayPipelineFactoryFactory = new DefaultRelayPipelineFactoryFactory(littleChainProxyManager, responseFilters, requestFilter, allChannels, timer)
    val factory = serverBootstrap.getPipelineFactory
    serverBootstrap.setPipelineFactory(new ChannelPipelineFactory {
      def getPipeline: ChannelPipeline = {
        val pipeline = factory.getPipeline
        if (proxyConfig.serverSSLEnable) {
          val engine = proxyConfig.serverSSLContext.createSSLEngine
          engine.setUseClientMode(false)
          engine.setNeedClientAuth(true)
          pipeline.addFirst("proxyServer-ssl", new SslHandler(engine))
        }

        val httpRequestHandler = new HttpRequestHandler(ProxyUtils.loadCacheManager, authenticationManager, allChannels, littleChainProxyManager, relayPipelineFactoryFactory, clientChannelFactory) {
          override def preConnect(clientBootstrap: ClientBootstrap, request: HttpRequest) {
            val factory: ChannelPipelineFactory = clientBootstrap.getPipelineFactory
            clientBootstrap.setPipelineFactory(new ChannelPipelineFactory {
              def getPipeline = {
                val pipeline: ChannelPipeline = factory.getPipeline
                if (chainProxyManager.getConnectHost(request.getUri).needForward && proxyConfig.proxyToServerSSLEnable) {
                  val engine = proxyConfig.clientSSLContext.createSSLEngine
                  engine.setUseClientMode(true)
                  pipeline.addFirst("proxyServerToRemote-ssl", new SslHandler(engine))
                }
                pipeline
              }
            })
          }
        }
        pipeline.replace(classOf[IdleRequestHandler], "idleAware", new IdleRequestHandler(httpRequestHandler))
        pipeline.replace(classOf[HttpRequestHandler], "handler", httpRequestHandler)
        pipeline

      }
    })
  }

}

