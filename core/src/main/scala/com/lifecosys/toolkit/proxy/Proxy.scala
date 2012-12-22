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
import group.ChannelGroupFuture
import org.jboss.netty.handler.logging.LoggingHandler
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.bootstrap.{ClientBootstrap, ServerBootstrap}
import org.jboss.netty.buffer.{ChannelBuffer, ChannelBuffers}
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConversions._
import org.jboss.netty.handler.ssl.SslHandler


/**
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 12/2/12 1:44 AM
 */
object ProxyServer {

  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  val connectProxyResponse: String = "HTTP/1.1 200 Connection established\r\n\r\n"

  val logger = LoggerFactory.getLogger(getClass)

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


  def closeChannel(channel: Channel) {
    if (logger.isDebugEnabled()) logger.debug("Closing channel: {}", channel)
    if (channel.isConnected) channel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
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

      pipeline.addLast("decoder", new HttpRequestDecoder(8192 * 2, 8192 * 4, 8192 * 4))
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
      serverBootstrap.setOption("tcpNoDelay", true);
      serverBootstrap.setOption("keepAlive", true);
      serverBootstrap.setOption("connectTimeoutMillis", 60 * 1000)
      serverBootstrap.setOption("child.keepAlive", true);
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
    val chainProxies = proxyConfig.chainProxies

    override def messageReceived(ctx: ChannelHandlerContext, me: MessageEvent) {
      val httpRequest = me.getMessage().asInstanceOf[HttpRequest]
      //      if (logger.isDebugEnabled()) logger.debug("Receive request: {} {} {}", httpRequest.getMethod, httpRequest.getUri, ctx.getChannel())
      if (logger.isDebugEnabled()) logger.debug("Receive request: {} ", httpRequest)
      val browserToProxyChannel = ctx.getChannel
      val requestTargetHost = Utils.parseHostAndPort(httpRequest.getUri)
      val host = chainProxies.get(0).getOrElse(requestTargetHost)
      def newClientBootstrap = {
        val proxyToServerBootstrap = new ClientBootstrap(proxyConfig.clientSocketChannelFactory)
        proxyToServerBootstrap.setOption("keepAlive", true)
        proxyToServerBootstrap.setOption("connectTimeoutMillis", 60 * 1000)
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
              case false => if (logger.isDebugEnabled()) logger.debug("Close browser connection..."); browserToProxyChannel.close
            }
          }
          def connectSuccessProcess(future: ChannelFuture) {
            if (logger.isDebugEnabled()) logger.debug("Connection successful: {}", future.getChannel)

            val pipeline = browserToProxyChannel.getPipeline
            pipeline.getNames.filterNot(List("logger", "ssl").contains(_)).foreach(pipeline remove _)

            pipeline.addLast("connectionHandler", new ConnectionRequestHandler(future.getChannel))
            chainProxies.get(0) match {
              case Some(chainedProxyServer) => {
                future.getChannel.getPipeline.addBefore("connectionHandler", "encoder", new HttpRequestEncoder)
                future.getChannel.write(httpRequest).addListener {
                  future: ChannelFuture => {
                    future.getChannel.getPipeline.remove("encoder")
                    if (logger.isDebugEnabled()) logger.debug("Finished write request to {} \n {} ", Array(future.getChannel, httpRequest))
                  }
                }
              }
              case None => {
                val wf = browserToProxyChannel.write(ChannelBuffers.copiedBuffer(connectProxyResponse.getBytes("UTF-8")))
                wf.addListener {
                  future: ChannelFuture => if (logger.isDebugEnabled()) logger.debug("Finished write request to {} \n {} ", Array(future.getChannel, connectProxyResponse))
                }
              }
            }

            ctx.getChannel.setReadable(true)
          }

          ctx.getChannel.setReadable(false)
          if (logger.isDebugEnabled()) logger.debug("Starting new connection to: {}", host)
          createProxyToServerBootstrap.connect(host).addListener(connectProcess _)
        }

        hostToChannelFuture.get(requestTargetHost) match {
          case Some(channel) => browserToProxyChannel.write(ChannelBuffers.copiedBuffer(connectProxyResponse.getBytes("UTF-8")))
          case None => initializeConnectProcess
        }
      }

      def processRequest(request: HttpRequest) {
        hostToChannelFuture.get(requestTargetHost) match {
          case Some(channel) if channel.isConnected => {
            logger.error("###########Use existed Proxy toserver conntection: {}################## Size {}##################", channel, hostToChannelFuture.size)
            channel.write(request)
          }
          case _ => {
            def connectProcess(future: ChannelFuture) {
              future.isSuccess match {
                case true => {
                  //                  hostToChannelFuture.put(host, future.getChannel)
                  future.getChannel().write(request).addListener {
                    future: ChannelFuture => if (logger.isDebugEnabled()) logger.debug("Write request to remote server {} completed.", future.getChannel)
                  }
                  ctx.getChannel.setReadable(true)
                }
                case false => if (logger.isDebugEnabled()) logger.debug("Close browser connection..."); closeChannel(browserToProxyChannel)
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
      if (logger.isDebugEnabled()) logger.debug("New channel opened: {}", e.getChannel)
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
      pipeline.addLast("codec", new HttpClientCodec(8192 * 2, 8192 * 4, 8192 * 4))
      // Remove the following line if you don't want automatic content decompression.
      pipeline.addLast("inflater", new HttpContentDecompressor)
      // Uncomment the following line if you don't want to handle HttpChunks.
      //            pipeline.addLast("aggregator", new HttpChunkAggregator(1024 * 10))
      pipeline.addLast("proxyToServerHandler", new HttpRelayingHandler(browserToProxyChannel))
    }


    override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      if (logger.isDebugEnabled()) logger.debug("Got closed event on : {}", e.getChannel)
    }


    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      logger.warn("Caught exception on proxy -> web connection: " + e.getChannel, e.getCause)
      closeChannel(e.getChannel)
    }
  }


  class HttpRelayingHandler(val browserToProxyChannel: Channel)(implicit proxyConfig: ProxyConfig) extends SimpleChannelUpstreamHandler {
    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
      if (logger.isDebugEnabled()) logger.debug("========{} receive message: =======\n {}", ctx.getChannel.asInstanceOf[Any], e.getMessage)

      if (browserToProxyChannel.isConnected) {
        browserToProxyChannel.write(e.getMessage)
      } else {
        if (e.getChannel.isConnected) {
          if (logger.isDebugEnabled()) logger.debug("Closing channel to remote server {}", e.getChannel)
          e.getChannel.close
        }
      }
    }

    override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      logger.debug("New channel opened: {}", e.getChannel)
      proxyConfig.allChannels.add(e.getChannel)
    }

    override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      if (logger.isDebugEnabled()) logger.debug("Got closed event on : {}", e.getChannel)
      closeChannel(browserToProxyChannel)
    }


    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      if (logger.isDebugEnabled()) logger.debug("Caught exception on proxy -> web connection: " + e.getChannel, e.getCause)
      closeChannel(e.getChannel)
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
      closeChannel(relayChannel)
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      logger.warn("Exception on: " + e.getChannel, e.getCause)
      closeChannel(e.getChannel)
    }
  }

}
