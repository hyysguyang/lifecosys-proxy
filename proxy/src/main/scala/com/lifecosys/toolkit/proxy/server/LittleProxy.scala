package com.lifecosys.toolkit.proxy.server

import com.lifecosys.toolkit.proxy._
import org.jboss.netty.logging.{ Slf4JLoggerFactory, InternalLoggerFactory }
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import com.typesafe.config.ConfigFactory
import org.littleshoot.proxy._
import org.jboss.netty.channel.{ ChannelPipeline, ChannelPipelineFactory }
import org.jboss.netty.handler.codec.http.{HttpHeaders, HttpRequest}
import org.jboss.netty.handler.ssl.SslHandler
import org.slf4j.LoggerFactory
import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.channel.socket.ClientSocketChannelFactory
import org.jboss.netty.util.Timer
import scala.Some
import org.littleshoot.proxy.ChainProxyManager
import com.lifecosys.toolkit.proxy.ProxyHost
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel.group.ChannelGroup
import com.lifecosys.toolkit.proxy
import com.lifecosys.toolkit.logging.Logger

/**
 *
 *
 * @author Young Gu
 * @version 1.0 6/6/13 3:46 PM
 */
object LittleProxy {
  logger = Logger()
  Utils.installJCEPolicy
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  Security.addProvider(new BouncyCastleProvider)

  def main(args: Array[String]) {

    val config = ConfigFactory.load()
    val proxyConfig = new ProgrammaticCertificationProxyConfig(Some(config))

    val localConfig = ConfigFactory.parseString(
      """
        |local=true
        |chain-proxy{
        |   host = "localhost 8081"
        |}
        |
        |proxy-server{
        |    thread {
        |        corePoolSize = 10
        |        maximumPoolSize = 30
        |    }
        |    ssl {
        |            enabled = false
        |    }
        |
        |}
        |
        |proxy-server-to-remote{
        |
        |    thread {
        |        corePoolSize = 10
        |        maximumPoolSize = 30
        |    }
        |    ssl {
        |            enabled = true
        |    }
        |}
      """.stripMargin).withFallback(config)

    val chainedProxyConfig = ConfigFactory.parseString(
      """
        |local=false
        |chain-proxy{
        |}
        |
        |proxy-server{
        |    thread {
        |        corePoolSize = 10
        |        maximumPoolSize = 30
        |    }
        |    ssl {
        |            enabled = true
        |    }
        |
        |}
        |
        |proxy-server-to-remote{
        |
        |    thread {
        |        corePoolSize = 10
        |        maximumPoolSize = 30
        |    }
        |    ssl {
        |            enabled = false
        |    }
        |}
      """.stripMargin).withFallback(config)

    val proxy = new LittleProxyServer(8080)(new GFWProgrammaticCertificationProxyConfig(Some(localConfig)))
    val server = new LittleProxyServer(8081)(new ProgrammaticCertificationProxyConfig(Some(chainedProxyConfig)))

    server.start()

    proxy.start()

  }



  class LittleProxyServer(port: Int)(implicit proxyConfig: ProxyConfig) extends org.littleshoot.proxy.DefaultHttpProxyServer(port) {
    val chainProxyManager = proxyConfig.getChainProxyManager
    val serverEngine=if (!proxyConfig.serverSSLEnable) null else {
      val engine = proxyConfig.serverSSLContext.createSSLEngine
      engine.setUseClientMode(false)
      engine.setNeedClientAuth(true)
      engine
    }

    val clientEngine=if (!proxyConfig.proxyToServerSSLEnable) null else {
      val engine = proxyConfig.clientSSLContext.createSSLEngine
      engine.setUseClientMode(true)
      engine
    }

    val littleChainProxyManager = if (proxyConfig.chainProxies.isEmpty) null else new ChainProxyManager() {
      def getChainProxy(request: HttpRequest): String = {
        val proxyHost: ProxyHost = chainProxyManager.getConnectHost(request.getUri)
        logger.debug("######################################################3")
        logger.debug(request)
        logger.debug(proxyHost.host.getHostString + ":"+proxyHost.host.getPort)
        logger.debug(ProxyUtils.parseHostAndPort(request))
        import scala.collection.JavaConversions._
        logger.debug(request.getHeaders(HttpHeaders.Names.HOST).toList.mkString("\t"))
        logger.debug("######################################################3")
        ProxyUtils.parseHostAndPort(request)
        proxyHost.host.getHostString + ":"+proxyHost.host.getPort
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
            pipeline.addFirst("proxyServer-ssl", new SslHandler(serverEngine))
          }

          val httpRequestHandler = new HttpRequestHandler(ProxyUtils.loadCacheManager, authenticationManager, allChannels, littleChainProxyManager, relayPipelineFactoryFactory, clientChannelFactory) {
            override def preConnect(clientBootstrap: ClientBootstrap,request:HttpRequest) {
              //                    if (isChainedProxy && proxyConfig.proxyToServerSSLEnable) {
              val factory: ChannelPipelineFactory = clientBootstrap.getPipelineFactory
              clientBootstrap.setPipelineFactory(new ChannelPipelineFactory {
                def getPipeline: ChannelPipeline = {
                  val pipeline: ChannelPipeline = factory.getPipeline
                  if(chainProxyManager.getConnectHost(request.getUri).isChained&&proxyConfig.proxyToServerSSLEnable){
                    pipeline.addFirst("proxyServerToRemote-ssl", new SslHandler(clientEngine))
                  }

                  if(!chainProxyManager.getConnectHost(request.getUri).isChained){
                    pipeline.get(classOf[ProxyHttpRequestEncoder]).keepProxyFormat=false
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
}

