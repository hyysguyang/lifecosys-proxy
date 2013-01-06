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

import org.jboss.netty.logging.{Slf4JLoggerFactory, InternalLoggerFactory}
import java.net.InetSocketAddress
import org.jboss.netty.channel._
import collection.mutable
import group.ChannelGroupFuture
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.bootstrap.ServerBootstrap
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConversions._
import org.jboss.netty.handler.ssl.SslHandler
import org.jboss.netty.handler.timeout.{IdleStateEvent, IdleStateAwareChannelHandler, IdleStateHandler}
import org.jboss.netty.util.HashedWheelTimer
import com.lifecosys.toolkit.proxy.ProxyServer._
import com.lifecosys.toolkit.Logger


/**
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 12/2/12 1:44 AM
 */

object ProxyServer {

  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
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

  val logger = Logger(getClass)

  val isStarted: AtomicBoolean = new AtomicBoolean(false)

  val serverBootstrap = new ServerBootstrap(proxyConfig.serverSocketChannelFactory)

  def proxyServerPipeline = (pipeline: ChannelPipeline) => {
    //      pipeline.addLast("logger", new LoggingHandler(proxyConfig.loggerLevel,false))
    if (proxyConfig.serverSSLEnable) {
      val engine = proxyConfig.serverSSLContext.createSSLEngine()
      engine.setUseClientMode(false)
      engine.setNeedClientAuth(true)
      pipeline.addLast("proxyServer-ssl", new SslHandler(engine))
    }

    if (!proxyConfig.isLocal) {
      pipeline.addLast("proxyServer-deflater", new IgnoreEmptyBufferZlibEncoder)
      pipeline.addLast("proxyServer-inflater", new IgnoreEmptyBufferZlibDecoder)
    }

    pipeline.addLast("proxyServer-decoder", new HttpRequestDecoder(8192 * 2, 8192 * 4, 8192 * 4))
    //      pipeline.addLast("aggregator", new ChunkAggregator(65536))
    pipeline.addLast("proxyServer-encoder", new HttpResponseEncoder())

    pipeline.addLast("proxyServer-idle", new IdleStateHandler(timer, 0, 0, 120))
    pipeline.addLast("proxyServer-idleAware", new IdleStateAwareChannelHandler {
      override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
        logger.debug("Channel idle........%s".format(e.getChannel))
        Utils.closeChannel(e.getChannel)
      }
    })
    pipeline.addLast("proxyServer-proxyHandler", new ProxyHandler)
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
          channelFuture => logger.warn("Can't close %s, case by %s".format(channelFuture.getChannel, channelFuture.getCause))
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
