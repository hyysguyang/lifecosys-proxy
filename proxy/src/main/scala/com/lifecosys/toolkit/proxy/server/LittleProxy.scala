package com.lifecosys.toolkit.proxy.server

import com.lifecosys.toolkit.proxy.{ ProxyConfig, ProgrammaticCertificationProxyConfig, Utils }
import org.jboss.netty.logging.{ InternalLogLevel, Slf4JLoggerFactory, InternalLoggerFactory }
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import com.typesafe.config.{ Config, ConfigFactory }
import org.littleshoot.proxy._
import org.jboss.netty.channel.{ ChannelEvent, Channels, ChannelPipeline, ChannelPipelineFactory }
import org.jboss.netty.handler.codec.http.{ HttpResponse, HttpRequestDecoder, HttpRequest }
import org.jboss.netty.handler.ssl.SslHandler
import scala.Some
import org.jboss.netty.handler.timeout.IdleStateHandler
import org.jboss.netty.buffer.ChannelBuffer
import java.util.concurrent.Future
import org.slf4j.LoggerFactory
import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.handler.logging.LoggingHandler
import org.jboss.netty.channel.socket.ClientSocketChannelFactory
import org.jboss.netty.util.Timer

/**
 *
 *
 * @author Young Gu
 * @version 1.0 6/6/13 3:46 PM
 */
object LittleProxy {
  val log = LoggerFactory.getLogger(getClass())
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

    val server = new LittleProxyServer(8081)(new ProgrammaticCertificationProxyConfig(Some(chainedProxyConfig)))
    val proxy = new LittleProxyServer(8080)(new ProgrammaticCertificationProxyConfig(Some(localConfig)))

    server.start()

    proxy.start()

  }

  import java.net.InetSocketAddress
  import java.util.Iterator
  import java.util.concurrent.Executor
  import java.util.concurrent.Executors
  import java.util.concurrent.ThreadFactory
  import java.util.concurrent.atomic.AtomicBoolean
  import org.jboss.netty.bootstrap.ServerBootstrap
  import org.jboss.netty.channel.Channel
  import org.jboss.netty.channel.ChannelFuture
  import org.jboss.netty.channel.group.ChannelGroup
  import org.jboss.netty.channel.group.ChannelGroupFuture
  import org.jboss.netty.channel.group.DefaultChannelGroup
  import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
  import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
  import org.jboss.netty.util.HashedWheelTimer
  import org.jboss.netty.util.ThreadNameDeterminer
  import org.jboss.netty.util.ThreadRenamingRunnable

  class LittleProxyServer(port: Int)(implicit proxyConfig: ProxyConfig) extends org.littleshoot.proxy.DefaultHttpProxyServer(port) {
    val cpm = if (!proxyConfig.isLocal) null else new ChainProxyManager() {
      def getChainProxy(httpRequest: HttpRequest): String = "127.0.0.1 :8081"
      def onCommunicationError(hostAndPort: String) {}
    }

    override protected def preBind(serverBootstrap: ServerBootstrap,
                                   allChannels: ChannelGroup,
                                   clientChannelFactory: ClientSocketChannelFactory,
                                   timer: Timer,
                                   authenticationManager: ProxyAuthorizationManager,
                                   responseFilters: HttpResponseFilters,
                                   requestFilter: HttpRequestFilter) {
      val relayPipelineFactoryFactory = new DefaultRelayPipelineFactoryFactory(cpm, responseFilters, requestFilter, allChannels, timer)
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

          val httpRequestHandler = new HttpRequestHandler(ProxyUtils.loadCacheManager, authenticationManager, allChannels, cpm, relayPipelineFactoryFactory, clientChannelFactory) {
            override def preConnect(clientBootstrap: ClientBootstrap) {
              //                    if (isChainedProxy && proxyConfig.proxyToServerSSLEnable) {
              if (proxyConfig.proxyToServerSSLEnable) {
                val engine = proxyConfig.clientSSLContext.createSSLEngine
                engine.setUseClientMode(true)
                val factory: ChannelPipelineFactory = clientBootstrap.getPipelineFactory
                clientBootstrap.setPipelineFactory(new ChannelPipelineFactory {
                  def getPipeline: ChannelPipeline = {
                    val pipeline: ChannelPipeline = factory.getPipeline
                    pipeline.addFirst("proxyServerToRemote-ssl", new SslHandler(engine))
                    pipeline
                  }
                })
              }
            }
          }
          pipeline.replace(classOf[IdleRequestHandler], "idleAware", new IdleRequestHandler(httpRequestHandler))
          pipeline.replace(classOf[HttpRequestHandler], "handler", httpRequestHandler)
          pipeline

        }
      })
    }

  }

  object DefaultLittleProxyServer {
    private def newClientThreadPool: Executor = {
      return Executors.newCachedThreadPool(new ThreadFactory {
        def newThread(r: Runnable): Thread = {
          val t: Thread = new Thread(r, "LittleProxy-NioClientSocketChannelFactory-Thread-" + ({
            num += 1;
            num - 1
          }))
          return t
        }

        private var num: Int = 0
      })
    }

    private def newServerThreadPool: Executor = {
      return Executors.newCachedThreadPool(new ThreadFactory {
        def newThread(r: Runnable): Thread = {
          val t: Thread = new Thread(r, "LittleProxy-NioServerSocketChannelFactory-Thread-" + ({
            num += 1;
            num - 1
          }))
          return t
        }

        private var num: Int = 0
      })
    }

    val allChannels: ChannelGroup = new DefaultChannelGroup("HTTP-Proxy-Server")
    val timer = new HashedWheelTimer()
    val NULL_HTTPRESPONSEFILTERS = new HttpResponseFilters {
      def getFilter(hostAndPort: String): HttpFilter = {
        return null
      }
    }

    val nioClientSocketChannelFactory = new NioClientSocketChannelFactory(newClientThreadPool, newClientThreadPool)
    val nioServerSocketChannelFactory = new NioServerSocketChannelFactory(newServerThreadPool, newServerThreadPool)
    val NULL_PROXYCACHEMANAGER = new ProxyCacheManager {
      def returnCacheHit(request: HttpRequest, channel: Channel): Boolean = {
        return false
      }

      def cache(originalRequest: HttpRequest, httpResponse: HttpResponse, response: AnyRef, encoded: ChannelBuffer): Future[String] = {
        return null
      }
    }

  }

  class DefaultLittleProxyServer(port: Int)(implicit proxyConfig: ProxyConfig) extends HttpProxyServer {

    import DefaultLittleProxyServer._

    private final val stopped: AtomicBoolean = new AtomicBoolean(false)

    Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler {
      def uncaughtException(t: Thread, e: Throwable) {
        //          log.error("Uncaught throwable", e)
      }
    })

    ThreadRenamingRunnable.setThreadNameDeterminer(ThreadNameDeterminer.CURRENT)

    val serverBootstrap = new ServerBootstrap(nioServerSocketChannelFactory)

    def start() {

      this.stopped.set(false)
      val factory = new DefaultHttpServerPipelineFactory
      serverBootstrap.setPipelineFactory(factory)
      val isa = new InetSocketAddress(port)

      val channel: Channel = serverBootstrap.bind(isa)
      allChannels.add(channel)
      Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
        def run {
          stop
        }
      }))
    }

    def stop {
      //      log.info("Shutting down proxy")
      if (stopped.get) {
        //        log.info("Already stopped")
        return
      }
      stopped.set(true)
      //      log.info("Closing all channels...")
      val future: ChannelGroupFuture = allChannels.close
      future.awaitUninterruptibly(10 * 1000)
      if (!future.isCompleteSuccess) {
        val iter: Iterator[ChannelFuture] = future.iterator
        while (iter.hasNext) {
          val cf: ChannelFuture = iter.next
          if (!cf.isSuccess) {
            log.warn("Cause of failure for {} is {}", Array(cf.getChannel, cf.getCause))
          }
        }
      }
      log.info("Stopping timer")
      timer.stop
      nioServerSocketChannelFactory.releaseExternalResources
      nioClientSocketChannelFactory.releaseExternalResources
      log.info("Done shutting down proxy")
    }

    def addProxyAuthenticationHandler(pah: ProxyAuthorizationHandler) {

    }

    def start(localOnly: Boolean, anyAddress: Boolean) = ???
  }

  class DefaultHttpServerPipelineFactory(implicit proxyConfig: ProxyConfig) extends ChannelPipelineFactory with AllConnectionData {

    import DefaultLittleProxyServer._

    private var numHandlers = 0

    val chainProxyManager: ChainProxyManager = new ChainProxyManager() {
      def getChainProxy(httpRequest: HttpRequest): String = "127.0.0.1 :8081"

      def onCommunicationError(hostAndPort: String) {}
    }

    val cpm = if (proxyConfig.isLocal) chainProxyManager else null

    val relayPipelineFactoryFactory =
      new DefaultRelayPipelineFactoryFactory(cpm, NULL_HTTPRESPONSEFILTERS, null, allChannels, timer)

    def getPipeline: ChannelPipeline = {
      val pipeline: ChannelPipeline = Channels.pipeline
      log.debug("Accessing pipeline")

      if (proxyConfig.serverSSLEnable) {
        val engine = proxyConfig.serverSSLContext.createSSLEngine
        engine.setUseClientMode(false)
        engine.setNeedClientAuth(true)
        pipeline.addLast("proxyServer-ssl", new SslHandler(engine))
      }

      pipeline.addLast("decoder", new HttpRequestDecoder(8192, 8192 * 2, 8192 * 2))
      pipeline.addLast("encoder", new ProxyHttpResponseEncoder(NULL_PROXYCACHEMANAGER))
      val httpRequestHandler: HttpRequestHandler = new HttpRequestHandler(NULL_PROXYCACHEMANAGER,
        new DefaultProxyAuthorizationManager,
        allChannels, cpm, relayPipelineFactoryFactory, nioClientSocketChannelFactory) {
        override def preConnect(clientBootstrap: ClientBootstrap) {
          //                    if (isChainedProxy && proxyConfig.proxyToServerSSLEnable) {
          if (proxyConfig.proxyToServerSSLEnable) {
            val engine = proxyConfig.clientSSLContext.createSSLEngine
            engine.setUseClientMode(true)
            val factory: ChannelPipelineFactory = clientBootstrap.getPipelineFactory
            clientBootstrap.setPipelineFactory(new ChannelPipelineFactory {
              def getPipeline: ChannelPipeline = {
                val pipeline: ChannelPipeline = factory.getPipeline
                pipeline.addFirst("proxyServerToRemote-ssl", new SslHandler(engine))
                pipeline
              }
            })
          }
        }
      }

      pipeline.addLast("idle", new IdleStateHandler(timer, 0, 0, 70))
      pipeline.addLast("idleAware", new IdleRequestHandler(httpRequestHandler))
      pipeline.addLast("handler", httpRequestHandler)
      this.numHandlers += 1

      return pipeline
    }

    def getNumRequestHandlers: Int = numHandlers
  }

}

