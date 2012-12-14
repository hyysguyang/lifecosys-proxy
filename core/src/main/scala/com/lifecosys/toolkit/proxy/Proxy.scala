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
import group.{DefaultChannelGroup, ChannelGroupFuture}
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


/**
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 12/2/12 1:44 AM
 */
object ProxyServer {

  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  val logger = LoggerFactory.getLogger(getClass)
  var isDebugged: InternalLogLevel = InternalLogLevel.INFO

  val serverSocketChannelFactory = new NioServerSocketChannelFactory(new ThreadPoolExecutor(10, 30, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable]), new ThreadPoolExecutor(10, 30, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable]))

  val clientSocketChannelFactory = new NioClientSocketChannelFactory(new ThreadPoolExecutor(10, 30, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable]), new ThreadPoolExecutor(10, 30, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable]))

  val allChannels = new DefaultChannelGroup("HTTP-Proxy-Server")
  val connectProxyResponse: String = "HTTP/1.1 200 Connection established\r\n\r\n"

  def apply(port: Int = 9050, serverSSLEnable: Boolean = false, proxyToServerSSLEnable: Boolean = false) = new Proxy(port, serverSSLEnable)(proxyToServerSSLEnable)


  def parseHostAndPort(uri: String) = {
    val hostPortPattern = """(.*//|^)([a-zA-Z\d.]+)\:*(\d*).*""".r
    val hostPortPattern(prefix, host, port) = uri
    new InetSocketAddress(host, Some(port).filter(_.trim.length > 0).getOrElse("80").toInt)
  }


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


  class Proxy(port: Int = 9050, serverSSLEnable: Boolean = false)(implicit proxyToServerSSLEnable: Boolean = false) {
    val chainProxies = mutable.MutableList[InetSocketAddress]()

    val stopped: AtomicBoolean = new AtomicBoolean(false)

    val serverBootstrap = new ServerBootstrap(serverSocketChannelFactory)

    def proxyServerPipeline = (pipeline: ChannelPipeline) => {
      if (isDebugged == InternalLogLevel.DEBUG) {
        pipeline.addLast("logger", new LoggingHandler(InternalLogLevel.DEBUG))
      }
      // Uncomment the following line if you want HTTPS
      if (serverSSLEnable) {
        val engine = getServerContext.createSSLEngine();
        engine.setUseClientMode(false);
        engine.setNeedClientAuth(true)
        pipeline.addLast("ssl", new SslHandler(engine));
      }

      pipeline.addLast("decoder", new HttpRequestDecoder());
      //      pipeline.addLast("aggregator", new HttpChunkAggregator(65536));
      pipeline.addLast("encoder", new HttpResponseEncoder());
      //      pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
      pipeline.addLast("proxyHandler", new ProxyHandler(chainProxies)(proxyToServerSSLEnable));
    }

    def shutdown = {
      logger.info("Shutting down proxy")
      stopped.get match {
        case true => logger.info("Already stopped")
        case _ => shutdown
      }
      def shutdown {
        stopped.set(true)

        logger.info("Closing all channels...")
        val future: ChannelGroupFuture = allChannels.close
        future.awaitUninterruptibly(10 * 1000)

        if (!future.isCompleteSuccess) {
          future.iterator().filterNot(_.isSuccess).foreach {
            channelFuture => logger.warn("Can't close {}, case by {}", Array(channelFuture.getChannel, channelFuture.getCause))
          }

          serverSocketChannelFactory.releaseExternalResources()
          clientSocketChannelFactory.releaseExternalResources()
          logger.info("Done shutting down proxy")
        }
      }

    }

    def start = {
      logger.info("Starting proxy server on " + port)
      serverBootstrap.setPipelineFactory(proxyServerPipeline)

      allChannels.add(serverBootstrap.bind(new InetSocketAddress(port)))

      Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
        def run {
          logger.info("Shutdown proxy server now.............")
          shutdown
        }
      }))
      logger.info("Proxy server started on " + port)
    }


  }

  class ProxyHandler(chainProxies: mutable.MutableList[InetSocketAddress])(implicit proxyToServerSSLEnable: Boolean) extends SimpleChannelUpstreamHandler {

    val hostToChannelFuture = mutable.Map[InetSocketAddress, ChannelFuture]()


    override def messageReceived(ctx: ChannelHandlerContext, me: MessageEvent) {
      val httpRequest = me.getMessage().asInstanceOf[HttpRequest]
      logger.debug("Receive request: {} {} {}", httpRequest.getMethod, httpRequest.getUri, ctx.getChannel())
      val browserToProxyChannel = ctx.getChannel
      val host = chainProxies.get(0).getOrElse(parseHostAndPort(httpRequest.getUri))
      def newClientBootstrap = {
        val proxyToServerBootstrap = new ClientBootstrap(clientSocketChannelFactory)
        proxyToServerBootstrap.setOption("connectTimeoutMillis", 40 * 1000)
        proxyToServerBootstrap
      }

      def processConnectionRequest(request: HttpRequest) {
        def initializeConnectProcess {
          def createProxyToServerBootstrap = {
            val proxyToServerBootstrap: ClientBootstrap = newClientBootstrap
            proxyToServerBootstrap.setPipelineFactory {
              pipeline: ChannelPipeline =>
                if (isDebugged == InternalLogLevel.DEBUG) {
                  pipeline.addLast("logger", new LoggingHandler(InternalLogLevel.DEBUG))
                }
                if (proxyToServerSSLEnable) {
                  val engine = getClientContext.createSSLEngine
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
              case false => logger.info("Close browser connection..."); browserToProxyChannel.close
            }
          }
          def connectSuccessProcess(future: ChannelFuture) {
            logger.info("Connection successful: {}", future.getChannel)

            val pipeline = browserToProxyChannel.getPipeline
            pipeline.getNames.filterNot(List("logger", "ssl").contains(_)).foreach(pipeline remove _)

            pipeline.addLast("connectionHandler", new ConnectionRequestHandler(future.getChannel))
            chainProxies.get(0) match {
              case Some(chainedProxyServer) => {
                future.getChannel.getPipeline.addBefore("connectionHandler", "encoder", new HttpRequestEncoder)
                future.getChannel.write(httpRequest).addListener {
                  future: ChannelFuture => {
                    future.getChannel.getPipeline.remove("encoder")
                    logger.info("Finished write request to {} \n {} ", Array(future.getChannel, httpRequest))
                  }
                }
              }
              case None => {
                val wf = browserToProxyChannel.write(ChannelBuffers.copiedBuffer(connectProxyResponse.getBytes("UTF-8")))
                wf.addListener {
                  future: ChannelFuture => logger.info("Finished write request to {} \n {} ", Array(future.getChannel, connectProxyResponse))
                }
              }
            }

            ctx.getChannel.setReadable(true)
          }

          ctx.getChannel.setReadable(false)
          logger.debug("Starting new connection to: {}", host)
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
                    future: ChannelFuture => logger.info("Write request to remote server {} completed.", future.getChannel)
                  }
                  ctx.getChannel.setReadable(true)
                }
                case false => logger.info("Close browser connection..."); browserToProxyChannel.close
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
      logger.debug("New channel opened: {}", e.getChannel)
      allChannels.add(e.getChannel)
      super.channelOpen(ctx, e)

    }

    def proxyToServerPipeline(browserToProxyChannel: Channel) = (pipeline: ChannelPipeline) => {
      if (isDebugged == InternalLogLevel.DEBUG) {
        pipeline.addLast("logger", new LoggingHandler(InternalLogLevel.DEBUG))
      }
      if (proxyToServerSSLEnable) {
        val engine = getClientContext.createSSLEngine
        engine.setUseClientMode(true)
        pipeline.addLast("ssl", new SslHandler(engine))
      }
      pipeline.addLast("codec", new HttpClientCodec(8192, 8192 * 2, 8192 * 2))
      // Remove the following line if you don't want automatic content decompression.
      pipeline.addLast("inflater", new HttpContentDecompressor)
      // Uncomment the following line if you don't want to handle HttpChunks.
      //      pipeline.addLast("aggregator", new HttpChunkAggregator(1048576));
      pipeline.addLast("proxyToServerHandler", new HttpRelayingHandler(browserToProxyChannel))
    }


    override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      logger.info("Got closed event on : {}", e.getChannel)
    }


    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      logger.info("Caught exception on proxy -> web connection: " + e.getChannel, e.getCause)

      if (e.getChannel.isConnected) {
        e.getChannel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
      }
    }
  }


  class HttpRelayingHandler(val browserToProxyChannel: Channel) extends SimpleChannelUpstreamHandler {
    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
      logger.debug("============================================================\n {}", e.getMessage.toString)

      if (browserToProxyChannel.isConnected) {
        browserToProxyChannel.write(e.getMessage)
      } else {
        if (e.getChannel.isConnected) {
          logger.info("Closing channel to remote server {}", e.getChannel)
          e.getChannel.close
        }
      }
    }

    override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      logger.debug("New channel opened: {}", e.getChannel)
      allChannels.add(e.getChannel)
    }

    override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      logger.info("Got closed event on : {}", e.getChannel)
      if (browserToProxyChannel.isConnected) {
        browserToProxyChannel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
      }

    }


    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      logger.info("Caught exception on proxy -> web connection: " + e.getChannel, e.getCause)

      if (e.getChannel.isConnected) {
        e.getChannel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
      }
    }
  }


  class ConnectionRequestHandler(relayChannel: Channel) extends SimpleChannelUpstreamHandler {
    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
      logger.debug("ConnectionRequestHandler-{} receive message:\n {}", Array(ctx.getChannel, e.getMessage))
      val msg: ChannelBuffer = e.getMessage.asInstanceOf[ChannelBuffer]
      if (relayChannel.isConnected) {
        relayChannel.write(msg)
      }
    }

    override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      val ch: Channel = e.getChannel
      logger.info("CONNECT channel opened on: {}", ch)
      allChannels.add(e.getChannel)
    }

    override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      logger.info("Got closed event on : {}", e.getChannel)
      if (relayChannel.isConnected) {
        relayChannel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
      }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      logger.info("Exception on: " + e.getChannel, e.getCause)
      if (e.getChannel.isConnected) {
        e.getChannel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
      }
    }
  }


  def getServerContext: SSLContext = {
    var serverContext: SSLContext = null
    try {
      val ks: KeyStore = KeyStore.getInstance("JKS")
      ks.load(getClass.getResourceAsStream("/binary/keystore/lifecosys-proxy-server-keystore.jks"), "killccp-server".toCharArray)
      val kmf: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
      kmf.init(ks, "killccp-server".toCharArray)
      val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
      val tks: KeyStore = KeyStore.getInstance("JKS")
      tks.load(getClass.getResourceAsStream("/binary/keystore/lifecosys-proxy-client-for-server-trust-keystore.jks"), "killccp-server".toCharArray)
      tmf.init(tks)
      serverContext = SSLContext.getInstance("SSL")
      serverContext.init(kmf.getKeyManagers, tmf.getTrustManagers, new SecureRandom)
    }
    catch {
      case e: Exception => {
        e.printStackTrace
      }
    }
    return serverContext
  }

  def getClientContext: SSLContext = {
    var clientContext: SSLContext = null
    try {
      val ks: KeyStore = KeyStore.getInstance("JKS")
      ks.load(getClass.getResourceAsStream("/binary/keystore/lifecosys-proxy-client-keystore.jks"), "killccp-server".toCharArray)
      val kmf: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
      kmf.init(ks, "killccp-server".toCharArray)
      val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
      val tks: KeyStore = KeyStore.getInstance("JKS")
      tks.load(getClass.getResourceAsStream("/binary/keystore/lifecosys-proxy-server-for-client-trust-keystore.jks"), "killccp-server".toCharArray)
      tmf.init(tks)
      clientContext = SSLContext.getInstance("SSL")
      clientContext.init(kmf.getKeyManagers, tmf.getTrustManagers, new SecureRandom)
    }
    catch {
      case e: Exception => {
        e.printStackTrace
      }
    }
    return clientContext
  }
}
