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

import org.jboss.netty.logging.{InternalLogLevel, Slf4JLoggerFactory, InternalLoggerFactory}
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import org.jboss.netty.channel._
import collection.mutable
import group.{ChannelGroup, DefaultChannelGroup, ChannelGroupFuture}
import org.jboss.netty.handler.logging.LoggingHandler
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.bootstrap.{ClientBootstrap, ServerBootstrap}
import socket.nio.{NioClientSocketChannelFactory, NioServerSocketChannelFactory}
import org.jboss.netty.buffer.{ChannelBuffer, ChannelBuffers}
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConversions._
import org.jboss.netty.handler.ssl.SslHandler
import javax.net.ssl.{TrustManagerFactory, KeyManagerFactory, SSLContext}
import java.security.{SecureRandom, KeyStore}
import java.util.concurrent.{SynchronousQueue, TimeUnit, ThreadPoolExecutor}
import java.io.InputStream
import com.typesafe.config.{ConfigFactory, Config}
import socket.{ClientSocketChannelFactory, ServerSocketChannelFactory}


/**
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 12/2/12 1:44 AM
 */
object ProxyServer {

  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  val connectProxyResponse: String = "HTTP/1.1 200 Connection established\r\n\r\n"

  val logger = LoggerFactory.getLogger(getClass)


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

  def parseHostAndPort(uri: String) = {
    val hostPortPattern = """(.*//|^)([a-zA-Z\d.]+)\:*(\d*).*""".r
    val hostPortPattern(prefix, host, port) = uri
    new InetSocketAddress(host, Some(port).filter(_.trim.length > 0).getOrElse("80").toInt)
  }


  class Proxy(val proxyConfig: ProxyConfig = new SimpleProxyConfig) {
    implicit val currentProxyConfig = proxyConfig

    val isStarted: AtomicBoolean = new AtomicBoolean(false)

    val serverBootstrap = new ServerBootstrap(proxyConfig.serverSocketChannelFactory)

    def proxyServerPipeline = (pipeline: ChannelPipeline) => {
      if (proxyConfig.loggerLevel == InternalLogLevel.DEBUG) {
        pipeline.addLast("logger", new LoggingHandler(InternalLogLevel.DEBUG))
      }
      if (proxyConfig.serverSSLEnable) {
        val engine = proxyConfig.serverSSLContext.createSSLEngine()
        engine.setUseClientMode(false)

        //FIXME:Need client auth to more secure
//        engine.setNeedClientAuth(true)
        pipeline.addLast("ssl", new SslHandler(engine))
      }

      pipeline.addLast("decoder", new HttpRequestDecoder())
      //      pipeline.addLast("aggregator", new HttpChunkAggregator(65536))
      pipeline.addLast("encoder", new HttpResponseEncoder())
      //      pipeline.addLast("chunkedWriter", new ChunkedWriteHandler())
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
    val chainProxies = proxyConfig.chainProxies

    val hostToChannelFuture = mutable.Map[InetSocketAddress, ChannelFuture]()


    override def messageReceived(ctx: ChannelHandlerContext, me: MessageEvent) {
      val httpRequest = me.getMessage().asInstanceOf[HttpRequest]
      if(logger.isDebugEnabled())logger.debug("Receive request: {} {} {}", httpRequest.getMethod, httpRequest.getUri, ctx.getChannel())
      val browserToProxyChannel = ctx.getChannel
      val host = chainProxies.get(0).getOrElse(parseHostAndPort(httpRequest.getUri))
      def newClientBootstrap = {
        val proxyToServerBootstrap = new ClientBootstrap(proxyConfig.clientSocketChannelFactory)
        proxyToServerBootstrap.setOption("connectTimeoutMillis", 40 * 1000)
        proxyToServerBootstrap
      }

      def processConnectionRequest(request: HttpRequest) {
        def initializeConnectProcess {
          def createProxyToServerBootstrap = {
            val proxyToServerBootstrap: ClientBootstrap = newClientBootstrap
            proxyToServerBootstrap.setPipelineFactory {
              pipeline: ChannelPipeline =>
                if (proxyConfig.loggerLevel == InternalLogLevel.DEBUG) {
                  pipeline.addLast("logger", new LoggingHandler(InternalLogLevel.DEBUG))
                }
                if (proxyToServerSSLEnable) {
                  val engine = proxyConfig.clientSSLContext.createSSLEngine
                  engine.setUseClientMode(true)
                  pipeline.addLast("ssl", new SslHandler(engine))
                }
                pipeline.addLast("connectionHandler", new ConnectionRequestHandler(browserToProxyChannel))
            }
            proxyToServerBootstrap
          }

          def connectProcess(future: ChannelFuture): Unit = {
            future.isSuccess match {
              case true => connectSuccessProcess(future)
              case false => if(logger.isDebugEnabled())logger.debug("Close browser connection..."); browserToProxyChannel.close
            }
          }
          def connectSuccessProcess(future: ChannelFuture) {
            if(logger.isDebugEnabled())logger.debug("Connection successful: {}", future.getChannel)

            val pipeline = browserToProxyChannel.getPipeline
            pipeline.getNames.filterNot(List("logger", "ssl").contains(_)).foreach(pipeline remove _)

            pipeline.addLast("connectionHandler", new ConnectionRequestHandler(future.getChannel))
            chainProxies.get(0) match {
              case Some(chainedProxyServer) => {
                future.getChannel.getPipeline.addBefore("connectionHandler", "encoder", new HttpRequestEncoder)
                future.getChannel.write(httpRequest).addListener {
                  future: ChannelFuture => {
                    future.getChannel.getPipeline.remove("encoder")
                    if(logger.isDebugEnabled())logger.debug("Finished write request to {} \n {} ", Array(future.getChannel, httpRequest))
                  }
                }
              }
              case None => {
                val wf = browserToProxyChannel.write(ChannelBuffers.copiedBuffer(connectProxyResponse.getBytes("UTF-8")))
                wf.addListener {
                  future: ChannelFuture => if(logger.isDebugEnabled())logger.debug("Finished write request to {} \n {} ", Array(future.getChannel, connectProxyResponse))
                }
              }
            }

            ctx.getChannel.setReadable(true)
          }

          ctx.getChannel.setReadable(false)
          if(logger.isDebugEnabled())logger.debug("Starting new connection to: {}", host)
          createProxyToServerBootstrap.connect(host).addListener(connectProcess _)
        }

        hostToChannelFuture.get(host) match {
          case Some(channelFuture) => browserToProxyChannel.write(ChannelBuffers.copiedBuffer(connectProxyResponse.getBytes("UTF-8")))
          case None => initializeConnectProcess
        }
      }

      def processRequest(request: HttpRequest) {
        hostToChannelFuture.get(host) match {
          case Some(channelFuture) => {
            channelFuture.getChannel().write(request)
          }
          case None => {
            def connectProcess(future: ChannelFuture) {
              future.isSuccess match {
                case true => {
                  future.getChannel().write(request).addListener {
                    future: ChannelFuture => if(logger.isDebugEnabled())logger.debug("Write request to remote server {} completed.", future.getChannel)
                  }
                  ctx.getChannel.setReadable(true)
                }
                case false => if(logger.isDebugEnabled())logger.debug("Close browser connection..."); browserToProxyChannel.close
              }
            }

            ctx.getChannel.setReadable(false)
            val proxyToServerBootstrap = newClientBootstrap
            proxyToServerBootstrap.setPipelineFactory(proxyToServerPipeline(browserToProxyChannel))
            proxyToServerBootstrap.connect(host).addListener(connectProcess _)
          }
        }
      }

      httpRequest match {
        case request: HttpRequest if HttpMethod.CONNECT == request.getMethod => processConnectionRequest(request)
        case request: HttpRequest => processRequest(request)
      }
    }


    override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      if(logger.isDebugEnabled())logger.debug("New channel opened: {}", e.getChannel)
      proxyConfig.allChannels.add(e.getChannel)
      super.channelOpen(ctx, e)

    }

    def proxyToServerPipeline(browserToProxyChannel: Channel) = (pipeline: ChannelPipeline) => {
      if (proxyConfig.loggerLevel == InternalLogLevel.DEBUG) {
        pipeline.addLast("logger", new LoggingHandler(InternalLogLevel.DEBUG))
      }
      if (proxyToServerSSLEnable) {
        val engine = proxyConfig.clientSSLContext.createSSLEngine
        engine.setUseClientMode(true)
        pipeline.addLast("ssl", new SslHandler(engine))
      }
      pipeline.addLast("codec", new HttpClientCodec(8192, 8192 * 2, 8192 * 2))
      // Remove the following line if you don't want automatic content decompression.
      pipeline.addLast("inflater", new HttpContentDecompressor)
      // Uncomment the following line if you don't want to handle HttpChunks.
      //      pipeline.addLast("aggregator", new HttpChunkAggregator(1048576))
      pipeline.addLast("proxyToServerHandler", new HttpRelayingHandler(browserToProxyChannel))
    }


    override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      if(logger.isDebugEnabled())logger.debug("Got closed event on : {}", e.getChannel)
    }


    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      logger.warn("Caught exception on proxy -> web connection: " + e.getChannel, e.getCause)

      if (e.getChannel.isConnected) {
        e.getChannel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
      }
    }
  }


  class HttpRelayingHandler(val browserToProxyChannel: Channel)(implicit proxyConfig: ProxyConfig) extends SimpleChannelUpstreamHandler {
    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
      if(logger.isDebugEnabled())logger.debug("============================================================\n {}", e.getMessage.toString)

      if (browserToProxyChannel.isConnected) {
        browserToProxyChannel.write(e.getMessage)
      } else {
        if (e.getChannel.isConnected) {
          if(logger.isDebugEnabled())logger.debug("Closing channel to remote server {}", e.getChannel)
          e.getChannel.close
        }
      }
    }

    override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      logger.debug("New channel opened: {}", e.getChannel)
      proxyConfig.allChannels.add(e.getChannel)
    }

    override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      if(logger.isDebugEnabled())logger.debug("Got closed event on : {}", e.getChannel)
      if (browserToProxyChannel.isConnected) {
        browserToProxyChannel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
      }

    }


    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      if(logger.isDebugEnabled())logger.debug("Caught exception on proxy -> web connection: " + e.getChannel, e.getCause)

      if (e.getChannel.isConnected) {
        e.getChannel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
      }
    }
  }


  class ConnectionRequestHandler(relayChannel: Channel)(implicit proxyConfig: ProxyConfig) extends SimpleChannelUpstreamHandler {
    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
      if(logger.isDebugEnabled())logger.debug("ConnectionRequestHandler-{} receive message:\n {}", Array(ctx.getChannel, e.getMessage))
      val msg: ChannelBuffer = e.getMessage.asInstanceOf[ChannelBuffer]
      if (relayChannel.isConnected) {
        relayChannel.write(msg)
      }
    }

    override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      val ch: Channel = e.getChannel
      if(logger.isDebugEnabled())logger.debug("CONNECT channel opened on: {}", ch)
      proxyConfig.allChannels.add(e.getChannel)
    }

    override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      if(logger.isDebugEnabled())logger.debug("Got closed event on : {}", e.getChannel)
      if (relayChannel.isConnected) {
        relayChannel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
      }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      logger.warn("Exception on: " + e.getChannel, e.getCause)
      if (e.getChannel.isConnected) {
        e.getChannel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
      }
    }
  }

}

trait SSLManager {
  def keyStorePassword: String
  def keyManagerKeyStoreInputStream: InputStream

  def trustKeyStorePassword: String
  def trustManagerKeyStoreInputStream: InputStream

  def getSSLContext = {
    val keyStore: KeyStore = KeyStore.getInstance("JKS")
    keyStore.load(keyManagerKeyStoreInputStream, keyStorePassword.toCharArray)
    val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(keyStore, keyStorePassword.toCharArray)
    val trustKeyStore: KeyStore = KeyStore.getInstance("JKS")
    trustKeyStore.load(trustManagerKeyStoreInputStream, trustKeyStorePassword.toCharArray)
    val trustManagerFactory: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    trustManagerFactory.init(trustKeyStore)
    val clientContext = SSLContext.getInstance("SSL")
    clientContext.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, new SecureRandom)
    clientContext
  }
}

trait ProxyConfig {

  val port: Int

  val serverSSLEnable: Boolean

  val proxyToServerSSLEnable: Boolean

  val loggerLevel: InternalLogLevel

  val chainProxies: mutable.MutableList[InetSocketAddress]

  val serverSocketChannelFactory: ServerSocketChannelFactory

  val clientSocketChannelFactory: ClientSocketChannelFactory

  val serverSSLContext: SSLContext

  val clientSSLContext: SSLContext


  val allChannels: ChannelGroup = new DefaultChannelGroup("HTTP-Proxy-Server")
}


class SimpleProxyConfig extends ProxyConfig {

  override val port = 9050

  override val serverSSLEnable = false

  override val proxyToServerSSLEnable = false

  override val loggerLevel: InternalLogLevel = InternalLogLevel.INFO

  override val chainProxies = mutable.MutableList[InetSocketAddress]()


  override val serverSocketChannelFactory = new NioServerSocketChannelFactory(new ThreadPoolExecutor(10, 30, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable]), new ThreadPoolExecutor(10, 30, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable]))

  override val clientSocketChannelFactory = new NioClientSocketChannelFactory(new ThreadPoolExecutor(10, 30, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable]), new ThreadPoolExecutor(10, 30, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable]))


  override val serverSSLContext = new SSLManager {
    override val keyStorePassword="killccp"
    override val keyManagerKeyStoreInputStream = classOf[SimpleProxyConfig].getResourceAsStream("/binary/keystore/lifecosys-proxy-server-keystore.jks")
    override val trustKeyStorePassword="killccp"
    override val trustManagerKeyStoreInputStream = classOf[SimpleProxyConfig].getResourceAsStream("/binary/keystore/lifecosys-proxy-client-for-server-trust-keystore.jks")
  }.getSSLContext

  override val clientSSLContext = new SSLManager {
    override val keyStorePassword="killccp"
    override val keyManagerKeyStoreInputStream = classOf[SimpleProxyConfig].getResourceAsStream("/binary/keystore/lifecosys-proxy-client-keystore.jks")
    override val trustKeyStorePassword="killccp"
    override val trustManagerKeyStoreInputStream = classOf[SimpleProxyConfig].getResourceAsStream("/binary/keystore/lifecosys-proxy-server-for-client-trust-keystore.jks")
  }.getSSLContext
}


class DefaultProxyConfig(config: Option[Config] = None) extends ProxyConfig {

  val thisConfig = config.getOrElse(ConfigFactory.load())

  val name = thisConfig.getString("name")

  val serverThreadCorePoolSize = thisConfig.getInt("proxy-server.thread.corePoolSize")
  val serverThreadMaximumPoolSize = thisConfig.getInt("proxy-server.thread.maximumPoolSize")
  val serverSSLKeystorePassword = thisConfig.getString("proxy-server.ssl.keystore-password")
  val serverSSLKeystorePath = thisConfig.getString("proxy-server.ssl.keystore-path")
  val serverSSLTrustKeystorePath = thisConfig.getString("proxy-server.ssl.trust-keystore-path")

  val proxyToServerThreadCorePoolSize = thisConfig.getInt("proxy-server-to-remote.thread.corePoolSize")
  val proxyToServerThreadMaximumPoolSize = thisConfig.getInt("proxy-server-to-remote.thread.maximumPoolSize")
  val proxyToServerSSLKeystorePassword = thisConfig.getString("proxy-server-to-remote.ssl.keystore-password")
  val proxyToServerSSLKeystorePath = thisConfig.getString("proxy-server-to-remote.ssl.keystore-path")
  val proxyToServerSSLTrustKeystorePath = thisConfig.getString("proxy-server-to-remote.ssl.trust-keystore-path")

  //  require(thisConfig.getInt("port") > 0&&thisConfig.getInt("port")<65535)
  //
  //  require(serverThreadCorePoolSize > 0)
  //  require(serverThreadMaximumPoolSize > 0)
  //  if (thisConfig.getBoolean("proxy-server.ssl.enabled")) {
  //    require(serverSSLKeystorePath != null)
  //    require(serverSSLTrustKeystorePath != null)
  //  }
  //  require(proxyToServerThreadCorePoolSize > 0)
  //  require(proxyToServerThreadMaximumPoolSize > 0)
  //  if (thisConfig.getBoolean("proxy-server-to-remote.ssl.enabled")) {
  //    require((proxyToServerSSLKeystorePath) != null)
  //    require(proxyToServerSSLTrustKeystorePath != null)
  //  }

  val serverExecutor = new ThreadPoolExecutor(serverThreadCorePoolSize, serverThreadMaximumPoolSize, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable])
  val clientExecutor = new ThreadPoolExecutor(proxyToServerThreadCorePoolSize, proxyToServerThreadMaximumPoolSize, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable])


  override val port = thisConfig.getInt("port")
  override val serverSSLEnable = thisConfig.getBoolean("proxy-server.ssl.enabled")
  override val proxyToServerSSLEnable = thisConfig.getBoolean("proxy-server-to-remote.ssl.enabled")

  override val loggerLevel = InternalLogLevel.valueOf(thisConfig.getString("logger-level"))

  override val serverSocketChannelFactory = new NioServerSocketChannelFactory(serverExecutor, serverExecutor)
  override val clientSocketChannelFactory = new NioClientSocketChannelFactory(clientExecutor, clientExecutor);

  override val chainProxies = thisConfig.getString("chain-proxy.host") match {
    case host: String if host.trim.length > 0 => mutable.MutableList[InetSocketAddress](ProxyServer.parseHostAndPort(thisConfig.getString("chain-proxy.host").replaceFirst(" ", ":")))
    case _ => mutable.MutableList[InetSocketAddress]()
  }

  override val serverSSLContext = serverSSLManager.getSSLContext

  override val clientSSLContext = clientSSLManager.getSSLContext


  def serverSSLManager= {
    new SSLManager {
      override val keyStorePassword = serverSSLKeystorePassword
      override val keyManagerKeyStoreInputStream = classOf[DefaultProxyConfig].getResourceAsStream(serverSSLKeystorePath)
      //FIXME: Trust all client, but we need trust manager
      override val trustKeyStorePassword = null
      override val trustManagerKeyStoreInputStream = null

//      override val trustKeyStorePassword = serverSSLKeystorePassword
//      override val trustManagerKeyStoreInputStream = classOf[DefaultProxyConfig].getResourceAsStream(serverSSLTrustKeystorePath)
      override def getSSLContext = {
        val keyStore: KeyStore = KeyStore.getInstance("JKS")
        keyStore.load(keyManagerKeyStoreInputStream, keyStorePassword.toCharArray)
        val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
        keyManagerFactory.init(keyStore, keyStorePassword.toCharArray)
        val clientContext = SSLContext.getInstance("SSL")
        clientContext.init(keyManagerFactory.getKeyManagers, null, new SecureRandom)
        clientContext
      }
    }
  }

  def clientSSLManager= {
    new SSLManager {
      override val keyStorePassword=null
      override val keyManagerKeyStoreInputStream = null
//      override val keyStorePassword=proxyToServerSSLKeystorePassword
//      override val keyManagerKeyStoreInputStream = classOf[DefaultProxyConfig].getResourceAsStream(proxyToServerSSLKeystorePath)
      override val trustKeyStorePassword=proxyToServerSSLKeystorePassword
      override val trustManagerKeyStoreInputStream = classOf[DefaultProxyConfig].getResourceAsStream(proxyToServerSSLTrustKeystorePath)
      //FIXME: Trust all client, but we need trust manager
      override def getSSLContext = {
//        val keyStore: KeyStore = KeyStore.getInstance("JKS")
//        keyStore.load(keyManagerKeyStoreInputStream, keyStorePassword.toCharArray)
//        val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
//        keyManagerFactory.init(keyStore, keyStorePassword.toCharArray)
        val trustKeyStore: KeyStore = KeyStore.getInstance("JKS")
        trustKeyStore.load(trustManagerKeyStoreInputStream, trustKeyStorePassword.toCharArray)
        val trustManagerFactory: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
        trustManagerFactory.init(trustKeyStore)
        val clientContext = SSLContext.getInstance("SSL")
        clientContext.init(null, trustManagerFactory.getTrustManagers, new SecureRandom)
        clientContext
      }
    }
  }



}
