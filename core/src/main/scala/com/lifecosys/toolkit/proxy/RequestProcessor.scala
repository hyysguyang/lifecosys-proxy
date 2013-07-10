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

import org.jboss.netty.handler.codec.http._
import org.jboss.netty.channel._
import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.handler.ssl.SslHandler
import org.jboss.netty.handler.timeout.{ IdleStateEvent, IdleStateAwareChannelHandler, IdleStateHandler }
import org.jboss.netty.buffer.{ ChannelBufferInputStream, ChannelBuffer, ChannelBuffers }
import org.jboss.netty.handler.codec.compression.{ ZlibEncoder, ZlibDecoder }
import org.jboss.netty.handler.codec.serialization.{ ClassResolvers, ObjectDecoder, ObjectEncoder }
import org.jboss.netty.handler.codec.oneone.{ OneToOneDecoder, OneToOneEncoder }
import org.littleshoot.proxy.ProxyUtils
import org.apache.commons.io.IOUtils
import org.jboss.netty.handler.codec.http
import org.jboss.netty.channel.socket.nio.NioSocketChannelConfig
import java.nio.channels.ClosedChannelException
import com.typesafe.scalalogging.slf4j.Logging

/**
 *
 *
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 1/1/13 5:50 PM
 */

trait RequestProcessor extends Logging {
  def process

  val httpRequest: HttpRequest
  val browserToProxyContext: ChannelHandlerContext
}

class DefaultRequestProcessor(request: HttpRequest, browserChannelContext: ChannelHandlerContext)(implicit proxyConfig: ProxyConfig) extends RequestProcessor {

  override val httpRequest: HttpRequest = request
  implicit override val browserToProxyContext = browserChannelContext

  val connectHost = proxyConfig.getChainProxyManager.getConnectHost(httpRequest.getUri).get
  val browserChannel = browserToProxyContext.getChannel

  //Can't play online video since we send the full url for http request,
  // Exactly, we need use the relative url to access the remote server.
  if (!connectHost.needForward) httpRequest.setUri(Utils.stripHost(httpRequest.getUri))

  val httpRequestEncoder = connectHost.serverType match {
    case WebProxyType ⇒ new WebProxyHttpRequestEncoder(connectHost, Host(httpRequest.getUri))
    case _            ⇒ new HttpRequestEncoder()
  }

  def process {
    logger.debug(s"Process request with $connectHost")
    hostToChannelFuture.remove(connectHost.host) match {
      case Some(channel) if channel.isConnected ⇒ {
        logger.error(s"###########Use existed Proxy to server conntection: $channel################## Size ${hostToChannelFuture.size}##################")
        channel.write(httpRequest)
      }
      case _ ⇒ {
        browserChannel.setReadable(false)
        val proxyToServerBootstrap = newClientBootstrap
        proxyToServerBootstrap.setFactory(proxyConfig.clientSocketChannelFactory)
        proxyToServerBootstrap.setPipelineFactory(proxyToServerPipeline)
        proxyToServerBootstrap.connect(connectHost.host.socketAddress).addListener(connectProcess _)
      }
    }
  }

  def connectProcess(future: ChannelFuture) {
    browserChannel.setReadable(true)

    if (future.isSuccess) {
      if (connectHost.serverType == WebProxyType) {
        List("proxyServer-encoder").foreach(browserChannel.getPipeline remove _)
      }
      //                  hostToChannelFuture.put(connectHost.host, future.getChannel)
      future.getChannel().write(httpRequest).addListener {
        future: ChannelFuture ⇒ logger.debug("Write request to remote server %s completed.".format(future.getChannel))
      }
    } else {
      logger.debug("Close browser connection...")
      browserChannel.close()
      //      Utils.closeChannel(browserToProxyChannel)

    }
  }

  def proxyToServerPipeline = (pipeline: ChannelPipeline) ⇒ {
    //pipeline.addLast("logger", new LoggingHandler(proxyConfig.loggerLevel))
    if (connectHost.serverType != WebProxyType && connectHost.needForward && proxyConfig.proxyToServerSSLEnable) {
      val engine = proxyConfig.clientSSLContext.createSSLEngine
      engine.setUseClientMode(true)
      pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
    }
    //    if (connectHost.serverType == WebProxyType) {
    //      val engine = Utils.trustAllSSLContext.createSSLEngine
    //      engine.setUseClientMode(true)
    //      pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
    //    }

    if (connectHost.serverType != WebProxyType && connectHost.needForward) {
      pipeline.addLast("proxyServerToRemote-inflater", new IgnoreEmptyBufferZlibDecoder)
      pipeline.addLast("proxyServerToRemote-objectDecoder", new ObjectDecoder(ClassResolvers.weakCachingResolver(null)))
      pipeline.addLast("proxyServerToRemote-decrypt", new DecryptDecoder)

      pipeline.addLast("proxyServerToRemote-deflater", new IgnoreEmptyBufferZlibEncoder)
      pipeline.addLast("proxyServerToRemote-objectEncoder", new ObjectEncoder)
      pipeline.addLast("proxyServerToRemote-encrypt", new EncryptEncoder)
    }
    pipeline.addLast("proxyServerToRemote-httpResponseDecoder", new HttpResponseDecoder(8192 * 2, 8192 * 4, 8192 * 4))
    pipeline.addLast("proxyServerToRemote-httpResponseEncoder", httpRequestEncoder)
    //    pipeline.addLast("proxyServerToRemote-innerHttpChunkAggregator", new InnerHttpChunkAggregator())
    pipeline.addLast("proxyServerToRemote-idle", new IdleStateHandler(timer, 0, 0, 120))
    pipeline.addLast("proxyServerToRemote-idleAware", new IdleStateAwareChannelHandler {
      override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
        logger.debug("Channel idle........%s".format(e.getChannel))
        Utils.closeChannel(e.getChannel)
      }
    })
    pipeline.addLast("proxyServerToRemote-proxyToServerHandler", new HttpRelayingHandler(browserChannel))
  }

}

abstract class HttpRequestProcessor(request: HttpRequest, browserChannelContext: ChannelHandlerContext)(implicit proxyConfig: ProxyConfig) extends RequestProcessor {

  override val httpRequest: HttpRequest = request
  implicit override val browserToProxyContext = browserChannelContext

  val connectHost = proxyConfig.getChainProxyManager.getConnectHost(httpRequest.getUri).get
  val browserChannel = browserToProxyContext.getChannel

  //Can't play online video since we send the full url for http request,
  // Exactly, we need use the relative url to access the remote server.
  if (!connectHost.needForward) httpRequest.setUri(Utils.stripHost(httpRequest.getUri))

  def httpRequestEncoder: HttpMessageEncoder

  def process {
    logger.debug(s"Process request with $connectHost")
    hostToChannelFuture.remove(connectHost.host) match {
      case Some(channel) if channel.isConnected ⇒ {
        logger.error(s"###########Use existed Proxy to server conntection: $channel################## Size ${hostToChannelFuture.size}##################")
        channel.write(httpRequest)
      }
      case _ ⇒ {
        browserChannel.setReadable(false)
        val proxyToServerBootstrap = newClientBootstrap
        proxyToServerBootstrap.setFactory(proxyConfig.clientSocketChannelFactory)
        proxyToServerBootstrap.setPipelineFactory(proxyToServerPipeline)
        proxyToServerBootstrap.connect(connectHost.host.socketAddress).addListener(connectProcess _)
      }
    }
  }

  def connectProcess(future: ChannelFuture) {
    browserChannel.setReadable(true)

    if (future.isSuccess) {
      //                  hostToChannelFuture.put(connectHost.host, future.getChannel)
      future.getChannel().write(httpRequest).addListener {
        future: ChannelFuture ⇒ logger.debug("Write request to remote server %s completed.".format(future.getChannel))
      }
    } else {
      logger.debug("Close browser connection...")
      browserChannel.close()
      //      Utils.closeChannel(browserToProxyChannel)

    }
  }

  def proxyToServerPipeline = (pipeline: ChannelPipeline) ⇒ {
    //    pipeline.addFirst("logger", new LoggingHandler(proxyConfig.loggerLevel))
    //    if (connectHost.serverType != WebProxyType && connectHost.needForward && proxyConfig.proxyToServerSSLEnable) {
    //      val engine = proxyConfig.clientSSLContext.createSSLEngine
    //      engine.setUseClientMode(true)
    //      pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
    //    }
    //    if (connectHost.serverType == WebProxyType) {
    //      val engine = Utils.trustAllSSLContext.createSSLEngine
    //      engine.setUseClientMode(true)
    //      pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
    //    }

    if (connectHost.serverType != WebProxyType && connectHost.needForward) {
      pipeline.addLast("proxyServerToRemote-inflater", new IgnoreEmptyBufferZlibDecoder)
      pipeline.addLast("proxyServerToRemote-objectDecoder", new ObjectDecoder(ClassResolvers.weakCachingResolver(null)))
      pipeline.addLast("proxyServerToRemote-decrypt", new DecryptDecoder)

      pipeline.addLast("proxyServerToRemote-deflater", new IgnoreEmptyBufferZlibEncoder)
      pipeline.addLast("proxyServerToRemote-objectEncoder", new ObjectEncoder)
      pipeline.addLast("proxyServerToRemote-encrypt", new EncryptEncoder)
    }
    pipeline.addLast("proxyServerToRemote-httpResponseDecoder", new HttpResponseDecoder(8192 * 2, 8192 * 4, 8192 * 4))
    pipeline.addLast("proxyServerToRemote-httpResponseEncoder", httpRequestEncoder)
    //    pipeline.addLast("proxyServerToRemote-innerHttpChunkAggregator", new InnerHttpChunkAggregator())
    pipeline.addLast("proxyServerToRemote-idle", new IdleStateHandler(timer, 0, 0, 120))
    pipeline.addLast("proxyServerToRemote-idleAware", new IdleStateAwareChannelHandler {
      override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
        logger.debug("Channel idle........%s".format(e.getChannel))
        Utils.closeChannel(e.getChannel)
      }
    })

  }

}

class DefaultHttpRequestProcessor(request: HttpRequest, browserChannelContext: ChannelHandlerContext)(implicit proxyConfig: ProxyConfig)
    extends HttpRequestProcessor(request, browserChannelContext) {

  override val httpRequestEncoder = new HttpRequestEncoder()
  override def proxyToServerPipeline = (pipeline: ChannelPipeline) ⇒ {
    if (connectHost.needForward && proxyConfig.proxyToServerSSLEnable) {
      val engine = proxyConfig.clientSSLContext.createSSLEngine
      engine.setUseClientMode(true)
      pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
    }
    super.proxyToServerPipeline(pipeline)
    pipeline.addLast("proxyServerToRemote-proxyToServerHandler", new HttpRelayingHandler(browserChannel))
  }
}
class WebProxyHttpRequestProcessor(request: HttpRequest, browserChannelContext: ChannelHandlerContext)(implicit proxyConfig: ProxyConfig)
    extends HttpRequestProcessor(request, browserChannelContext) {

  require(connectHost.needForward, "The message must be need forward to remote WebProxy.")
  override val httpRequestEncoder = new WebProxyHttpRequestEncoder(connectHost, Host(httpRequest.getUri))

  override def connectProcess(future: ChannelFuture) {
    browserChannel.getPipeline remove classOf[HttpResponseEncoder]
    super.connectProcess(future)
  }

  override def proxyToServerPipeline = (pipeline: ChannelPipeline) ⇒ {
    if (proxyConfig.proxyToServerSSLEnable) {
      val engine = proxyConfig.clientSSLContext.createSSLEngine
      engine.setUseClientMode(true)
      pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
    }

    super.proxyToServerPipeline(pipeline)
    pipeline.addLast("proxyServerToRemote-proxyToServerHandler", new WebProxyHttpRelayingHandler(browserChannel))
  }
}
class ConnectionRequestProcessor(request: HttpRequest, browserChannelContext: ChannelHandlerContext)(implicit proxyConfig: ProxyConfig)
    extends RequestProcessor {
  require(request.getMethod == HttpMethod.CONNECT)

  override val httpRequest: HttpRequest = request

  implicit val browserToProxyContext = browserChannelContext

  val connectHost = proxyConfig.getChainProxyManager.getConnectHost(httpRequest.getUri).get

  val httpRequestEncoder = connectHost.serverType match {
    case WebProxyType ⇒ new WebProxyHttpRequestEncoder(connectHost, Host(request.getUri))
    case _            ⇒ new HttpRequestEncoder()
  }
  //  require(connectHost.serverType != WebProxyType, "Web proxy don't support HTTPS access.")

  val browserChannel = browserToProxyContext.getChannel

  def process {
    logger.debug("##########ConnectionRequestProcessor################Process request with %s".format((connectHost.host, connectHost.needForward)))
    hostToChannelFuture.get(connectHost.host) match {
      case Some(channel) if channel.isConnected ⇒ browserChannel.write(ChannelBuffers.copiedBuffer(Utils.connectProxyResponse.getBytes("UTF-8")))
      case None ⇒ {
        if (connectHost.serverType == WebProxyType) {
          val pipeline = browserChannel.getPipeline
          //Remove codec related handle for connect request, it's necessary for HTTPS.
          List("proxyServer-encoder", "proxyServer-decoder", "proxyServer-proxyHandler").foreach(pipeline remove _)
          pipeline.addLast("proxyServer-connectionHandler", new WebProxyHttpsRequestHandler(connectHost, Host(request.getUri)))

          //                                    val encode = classOf[HttpRequestEncoder].getSuperclass.getDeclaredMethods.filter(_.getName == "encode")(0)
          //                                    encode.setAccessible(true)
          //                                    encode.invoke(new HttpRequestEncoder(), null, channel, msg.asInstanceOf[Object]).asInstanceOf[ChannelBuffer]

          val connectionMessage = ChannelBuffers.wrappedBuffer(HttpMethod.CONNECT.getName.getBytes(Utils.UTF8))
          Channels.fireMessageReceived(browserChannel, connectionMessage)

          //          future.getChannel.write(httpRequest).addListener {
          //            writeFuture: ChannelFuture ⇒
          //            {
          //
          //              if (connectHost.serverType != WebProxyType) {
          //                writeFuture.getChannel.getPipeline.remove("proxyServerToRemote-encoder")
          //              }
          //              logger.debug("Finished write request to %s\n %s ".format(future.getChannel, httpRequest))
          //            }
          //          }

          //          browserToProxyChannel.write(ChannelBuffers.copiedBuffer(Utils.connectProxyResponse.getBytes("UTF-8"))).addListener {
          //            future: ChannelFuture ⇒ logger.debug("Finished write request to %s \n %s ".format(future.getChannel, Utils.connectProxyResponse))
          //          }

        } else {
          logger.debug("Starting new connection to: %s".format(connectHost.host))
          browserChannel.setReadable(false)
          createProxyToServerBootstrap.connect(connectHost.host.socketAddress).addListener(connectComplete _)

        }

      }
    }
  }

  def createProxyToServerBootstrap = {
    val proxyToServerBootstrap = newClientBootstrap
    proxyToServerBootstrap.setFactory(proxyConfig.clientSocketChannelFactory)
    proxyToServerBootstrap.setPipelineFactory {
      pipeline: ChannelPipeline ⇒

        //pipeline.addLast("logger", new LoggingHandler(proxyConfig.loggerLevel))

        if (connectHost.serverType != WebProxyType && connectHost.needForward && proxyConfig.proxyToServerSSLEnable) {
          val engine = proxyConfig.clientSSLContext.createSSLEngine
          engine.setUseClientMode(true)
          pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
        }

        //        if (connectHost.serverType == WebProxyType) {
        //          val engine = Utils.trustAllSSLContext.createSSLEngine
        //          engine.setUseClientMode(true)
        //          pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
        //        }

        if (connectHost.serverType != WebProxyType && connectHost.needForward) {
          pipeline.addLast("proxyServerToRemote-inflater", new IgnoreEmptyBufferZlibDecoder)
          pipeline.addLast("proxyServerToRemote-objectDecoder", new ObjectDecoder(ClassResolvers.weakCachingResolver(null)))
          pipeline.addLast("proxyServerToRemote-decrypt", new DecryptDecoder)

          pipeline.addLast("proxyServerToRemote-deflater", new IgnoreEmptyBufferZlibEncoder)
          pipeline.addLast("proxyServerToRemote-objectEncoder", new ObjectEncoder)
          pipeline.addLast("proxyServerToRemote-encrypt", new EncryptEncoder)
        }

        pipeline.addLast("proxyServerToRemote-idle", new IdleStateHandler(timer, 0, 0, 120))
        pipeline.addLast("proxyServerToRemote-idleAware", new IdleStateAwareChannelHandler {
          override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
            logger.debug("Channel idle........%s".format(e.getChannel))
            Utils.closeChannel(e.getChannel)
          }
        })

        pipeline.addLast("proxyServerToRemote-connectionHandler", new ConnectionRequestHandler(browserChannel))
    }
    proxyToServerBootstrap
  }

  def connectComplete(future: ChannelFuture): Unit = {
    logger.debug("Connection successful: %s".format(future.getChannel))

    if (!future.isSuccess) {
      logger.debug("Close browser connection...")
      Utils.closeChannel(browserChannel)
      return
    }

    val pipeline = browserChannel.getPipeline
    //Remove codec related handle for connect request, it's necessary for HTTPS.
    List("proxyServer-encoder", "proxyServer-decoder", "proxyServer-proxyHandler").foreach(pipeline remove _)
    //    if (connectHost.serverType == WebProxyType)
    //      pipeline.addLast("proxyServer-connectionHandler", new WebProxyHttpsRequestHandler(connectHost, Host(request.getUri)))
    //    else
    pipeline.addLast("proxyServer-connectionHandler", new ConnectionRequestHandler(future.getChannel))

    //    if (connectHost.serverType == WebProxyType) {
    //      future.getChannel.getPipeline.addBefore("proxyServerToRemote-connectionHandler", "proxyServerToRemote-decoder", new HttpResponseDecoder(8192 * 2, 8192 * 4, 8192 * 4))
    //    }
    //    future.getChannel.getPipeline.addBefore("proxyServerToRemote-connectionHandler", "proxyServerToRemote-encoder", httpRequestEncoder)

    def sendRequestToChainedProxy {

      if (connectHost.serverType == WebProxyType) {
        future.getChannel.getPipeline.addBefore("proxyServerToRemote-connectionHandler", "proxyServerToRemote-decoder", new HttpResponseDecoder(8192 * 2, 8192 * 4, 8192 * 4))
      }
      future.getChannel.getPipeline.addBefore("proxyServerToRemote-connectionHandler", "proxyServerToRemote-encoder", httpRequestEncoder)

      future.getChannel.write(httpRequest).addListener {
        writeFuture: ChannelFuture ⇒
          {

            if (connectHost.serverType != WebProxyType) {
              writeFuture.getChannel.getPipeline.remove("proxyServerToRemote-encoder")
            }
            logger.debug("Finished write request to %s\n %s ".format(future.getChannel, httpRequest))
          }
      }
    }

    if (connectHost.needForward)
      sendRequestToChainedProxy
    else browserChannel.write(ChannelBuffers.copiedBuffer(Utils.connectProxyResponse.getBytes("UTF-8"))).addListener {
      future: ChannelFuture ⇒ logger.debug("Finished write request to %s \n %s ".format(future.getChannel, Utils.connectProxyResponse))
    }

    browserChannel.setReadable(true)
  }
}

class NetHttpsRequestProcessor(request: HttpRequest, browserChannelContext: ChannelHandlerContext)(implicit proxyConfig: ProxyConfig)
    extends HttpsRequestProcessor(request, browserChannelContext) {

  protected def connect {
    logger.debug("Starting new connection to: %s".format(connectHost.host))
    browserChannel.setReadable(false)
    createProxyToServerBootstrap.connect(connectHost.host.socketAddress).addListener(connectComplete _)
  }
}
class WebProxyHttpsRequestProcessor(request: HttpRequest, browserChannelContext: ChannelHandlerContext)(implicit proxyConfig: ProxyConfig)
    extends HttpsRequestProcessor(request, browserChannelContext) {

  protected def connect {
    val pipeline = browserToProxyContext.getChannel.getPipeline
    //Remove codec related handle for connect request, it's necessary for HTTPS.
    List("proxyServer-encoder", "proxyServer-decoder", "proxyServer-proxyHandler").foreach(pipeline remove _)
    pipeline.addLast("proxyServer-connectionHandler", new WebProxyHttpsRequestHandler(connectHost, Host(request.getUri)))
    val connectionMessage = ChannelBuffers.wrappedBuffer(HttpMethod.CONNECT.getName.getBytes(Utils.UTF8))
    Channels.fireMessageReceived(browserChannel, connectionMessage)
  }
}
abstract class HttpsRequestProcessor(request: HttpRequest, browserChannelContext: ChannelHandlerContext)(implicit proxyConfig: ProxyConfig)
    extends RequestProcessor {
  require(request.getMethod == HttpMethod.CONNECT)

  override val httpRequest: HttpRequest = request

  implicit val browserToProxyContext = browserChannelContext

  val connectHost = proxyConfig.getChainProxyManager.getConnectHost(httpRequest.getUri).get

  val httpRequestEncoder = connectHost.serverType match {
    case WebProxyType ⇒ new WebProxyHttpRequestEncoder(connectHost, Host(request.getUri))
    case _            ⇒ new HttpRequestEncoder()
  }
  //  require(connectHost.serverType != WebProxyType, "Web proxy don't support HTTPS access.")

  val browserChannel = browserToProxyContext.getChannel

  def process {
    logger.debug("##########ConnectionRequestProcessor################Process request with %s".format((connectHost.host, connectHost.needForward)))
    hostToChannelFuture.get(connectHost.host) match {
      case Some(channel) if channel.isConnected ⇒ browserChannel.write(ChannelBuffers.copiedBuffer(Utils.connectProxyResponse.getBytes("UTF-8")))
      case None                                 ⇒ connect
    }
  }

  protected def connect

  def createProxyToServerBootstrap = {
    val proxyToServerBootstrap = newClientBootstrap
    proxyToServerBootstrap.setFactory(proxyConfig.clientSocketChannelFactory)
    proxyToServerBootstrap setPipelineFactory proxyToServerPipeline
    proxyToServerBootstrap
  }

  def proxyToServerPipeline = (pipeline: ChannelPipeline) ⇒ {

    //    pipeline.addFirst("logger", new LoggingHandler(proxyConfig.loggerLevel))

    if (connectHost.serverType != WebProxyType && connectHost.needForward && proxyConfig.proxyToServerSSLEnable) {
      val engine = proxyConfig.clientSSLContext.createSSLEngine
      engine.setUseClientMode(true)
      pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
    }

    if (connectHost.serverType != WebProxyType && connectHost.needForward) {
      pipeline.addLast("proxyServerToRemote-inflater", new IgnoreEmptyBufferZlibDecoder)
      pipeline.addLast("proxyServerToRemote-objectDecoder", new ObjectDecoder(ClassResolvers.weakCachingResolver(null)))
      pipeline.addLast("proxyServerToRemote-decrypt", new DecryptDecoder)

      pipeline.addLast("proxyServerToRemote-deflater", new IgnoreEmptyBufferZlibEncoder)
      pipeline.addLast("proxyServerToRemote-objectEncoder", new ObjectEncoder)
      pipeline.addLast("proxyServerToRemote-encrypt", new EncryptEncoder)
    }

    pipeline.addLast("proxyServerToRemote-idle", new IdleStateHandler(timer, 0, 0, 120))
    pipeline.addLast("proxyServerToRemote-idleAware", new IdleStateAwareChannelHandler {
      override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
        logger.debug("Channel idle........%s".format(e.getChannel))
        Utils.closeChannel(e.getChannel)
      }
    })

    pipeline.addLast("proxyServerToRemote-connectionHandler", new ConnectionRequestHandler(browserChannel))
  }

  def connectComplete(future: ChannelFuture): Unit = {
    logger.debug("Connection successful: %s".format(future.getChannel))

    if (!future.isSuccess) {
      logger.debug("Close browser connection...")
      Utils.closeChannel(browserChannel)
      return
    }

    val pipeline = browserChannel.getPipeline
    //Remove codec related handle for connect request, it's necessary for HTTPS.
    List("proxyServer-encoder", "proxyServer-decoder", "proxyServer-proxyHandler").foreach(pipeline remove _)
    //    if (connectHost.serverType == WebProxyType)
    //      pipeline.addLast("proxyServer-connectionHandler", new WebProxyHttpsRequestHandler(connectHost, Host(request.getUri)))
    //    else
    pipeline.addLast("proxyServer-connectionHandler", new ConnectionRequestHandler(future.getChannel))

    //    if (connectHost.serverType == WebProxyType) {
    //      future.getChannel.getPipeline.addBefore("proxyServerToRemote-connectionHandler", "proxyServerToRemote-decoder", new HttpResponseDecoder(8192 * 2, 8192 * 4, 8192 * 4))
    //    }
    //    future.getChannel.getPipeline.addBefore("proxyServerToRemote-connectionHandler", "proxyServerToRemote-encoder", httpRequestEncoder)

    def sendRequestToChainedProxy {

      if (connectHost.serverType == WebProxyType) {
        future.getChannel.getPipeline.addBefore("proxyServerToRemote-connectionHandler", "proxyServerToRemote-decoder", new HttpResponseDecoder(8192 * 2, 8192 * 4, 8192 * 4))
      }
      future.getChannel.getPipeline.addBefore("proxyServerToRemote-connectionHandler", "proxyServerToRemote-encoder", httpRequestEncoder)

      future.getChannel.write(httpRequest).addListener {
        writeFuture: ChannelFuture ⇒
          {

            if (connectHost.serverType != WebProxyType) {
              writeFuture.getChannel.getPipeline.remove("proxyServerToRemote-encoder")
            }
            logger.debug("Finished write request to %s\n %s ".format(future.getChannel, httpRequest))
          }
      }
    }

    if (connectHost.needForward)
      sendRequestToChainedProxy
    else browserChannel.write(ChannelBuffers.copiedBuffer(Utils.connectProxyResponse.getBytes("UTF-8"))).addListener {
      future: ChannelFuture ⇒ logger.debug("Finished write request to %s \n %s ".format(future.getChannel, Utils.connectProxyResponse))
    }

    browserChannel.setReadable(true)
  }
}

class WebProxyHttpsRequestHandler(connectHost: ConnectHost, proxyHost: Host)(implicit proxyConfig: ProxyConfig)
    extends SimpleChannelUpstreamHandler with Logging {

  def createProxyToServerBootstrap(implicit browserChannelContext: ChannelHandlerContext) = {
    val proxyToServerBootstrap = newClientBootstrap
    proxyToServerBootstrap.setFactory(proxyConfig.clientSocketChannelFactory)
    proxyToServerBootstrap.setPipelineFactory {
      pipeline: ChannelPipeline ⇒

        //pipeline.addLast("logger", new LoggingHandler(proxyConfig.loggerLevel))

        if (proxyConfig.proxyToServerSSLEnable) {
          val engine = proxyConfig.clientSSLContext.createSSLEngine
          engine.setUseClientMode(true)
          pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
        }

        pipeline.addLast("proxyServerToRemote-decoder", new HttpResponseDecoder(8192 * 2, 8192 * 4, 8192 * 4))
        pipeline.addLast("proxyServerToRemote-encoder", new WebProxyHttpRequestEncoder(connectHost, proxyHost))

        pipeline.addLast("proxyServerToRemote-idle", new IdleStateHandler(timer, 0, 0, 120))
        pipeline.addLast("proxyServerToRemote-idleAware", new IdleStateAwareChannelHandler {
          override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
            logger.debug("Channel idle........%s".format(e.getChannel))
            Utils.closeChannel(e.getChannel)
          }
        })

        pipeline.addLast("proxyServerToRemote-connectionHandler", new ConnectionRequestHandler(browserChannelContext.getChannel))
    }
    proxyToServerBootstrap
  }

  val channels = scala.collection.mutable.MutableList[Channel]()
  override def messageReceived(browserChannelContext: ChannelHandlerContext, e: MessageEvent) {
    logger.error(s"${browserChannelContext.getChannel} Receive message:\n ${Utils.formatMessage(e.getMessage)}")

    val browserChannel = browserChannelContext.getChannel
    browserChannel.setReadable(false)
    createProxyToServerBootstrap(browserChannelContext).connect(connectHost.host.socketAddress).addListener(connectComplete _)

    def connectComplete(future: ChannelFuture): Unit = {
      logger.debug(s"Connect to ${future.getChannel} successful")
      browserChannel.setReadable(true)
      if (!future.isSuccess) {
        logger.debug("Close browser connection...")
        Utils.closeChannel(browserChannel)
        return
      }

      val channel = future.getChannel
      //      synchronized(channels += channel)
      channel.write(e.getMessage).addListener {
        writeFuture: ChannelFuture ⇒ logger.debug(s"Finished write request to ${future.getChannel}\n ")
      }
    }

  }

  override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    val ch: Channel = e.getChannel
    logger.debug("CONNECT channel opened on: %s".format(ch))
    proxyConfig.allChannels.add(e.getChannel)
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.debug("Got closed event on : %s".format(e.getChannel))
    //    channels.foreach(Utils.closeChannel(_))
    //    synchronized(channels.clear())
    //        Utils.closeChannel(relayChannel)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn("Caught exception on : %s".format(e.getChannel), e.getCause)
    e.getCause match {
      case closeException: ClosedChannelException ⇒ //Just ignore it
      case exception                              ⇒ Utils.closeChannel(e.getChannel)
    }
  }
}

class InnerHttpChunkAggregator(maxContentLength: Int = 1024 * 128) extends HttpChunkAggregator(maxContentLength) {
  var cumulatedThunk: Option[HttpChunk] = None

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    e.getMessage match {
      case response: HttpMessage ⇒ ctx.sendUpstream(e)
      case chunk: HttpChunk if chunk.isLast ⇒ {
        if (cumulatedThunk isDefined) {
          Channels.fireMessageReceived(ctx, cumulatedThunk.get, e.getRemoteAddress)
          cumulatedThunk = None
        }
        ctx.sendUpstream(e)
      }
      case chunk: HttpChunk ⇒ {

        if (!cumulatedThunk.isDefined)
          cumulatedThunk = Some(chunk)
        else
          cumulatedThunk.get.setContent(ChannelBuffers.wrappedBuffer(cumulatedThunk.get.getContent, chunk.getContent))

        if (cumulatedThunk.get.getContent.readableBytes() > maxContentLength) {
          Channels.fireMessageReceived(ctx, cumulatedThunk.get, e.getRemoteAddress)
          cumulatedThunk = None
        }
      }
      case _ ⇒ ctx.sendUpstream(e)
    }
  }
}

/**
 * We need it to wrapper the buffer byte array to avoid the padding and block size process.
 * @param data
 */
sealed case class EncryptDataWrapper(data: Array[Byte])

class EncryptEncoder extends OneToOneEncoder {
  override def encode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = if (msg.isInstanceOf[ChannelBuffer])
    EncryptDataWrapper(Utils.cryptor.encrypt(ChannelBuffers.copiedBuffer(msg.asInstanceOf[ChannelBuffer]).array()))
  else
    msg
}

class DecryptDecoder extends OneToOneDecoder {
  def decode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = msg match {
    case EncryptDataWrapper(data) ⇒ ChannelBuffers.copiedBuffer(Utils.cryptor.decrypt(data))
    case _                        ⇒ msg
  }
}

class IgnoreEmptyBufferZlibEncoder extends ZlibEncoder {
  override def encode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = msg match {
    case cb: ChannelBuffer if (cb.hasArray) ⇒ super.encode(ctx, channel, msg).asInstanceOf[ChannelBuffer]
    case _                                  ⇒ msg
  }
}

class IgnoreEmptyBufferZlibDecoder extends ZlibDecoder {
  override def decode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = msg match {
    case cb: ChannelBuffer if (cb.hasArray) ⇒ super.decode(ctx, channel, msg).asInstanceOf[ChannelBuffer]
    case _                                  ⇒ msg
  }
}

class WebProxyHttpRequestEncoder(connectHost: ConnectHost, proxyHost: Host)(implicit browserChannelContext: ChannelHandlerContext)
    extends HttpRequestEncoder with Logging {
  val browserChannel = browserChannelContext.getChannel
  override def encode(ctx: ChannelHandlerContext, channel: Channel, msg: Any): AnyRef = {

    val toBeSentMessage = msg match {
      case request: HttpRequest ⇒ { //TODO:Maybe we can remove this since we should only receive channel buffer
        val encodedProxyRequest = super.encode(ctx, channel, ProxyUtils.copyHttpRequest(request, false)).asInstanceOf[ChannelBuffer]
        logger.debug("Encoded proxy request:\n" + IOUtils.toString(new ChannelBufferInputStream(ChannelBuffers.copiedBuffer(encodedProxyRequest))))
        val wrappedRequest = createWrappedRequest
        wrappedRequest.setHeader("proxyRequestMethod", request.getMethod)
        wrappedRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, encodedProxyRequest.readableBytes().toString)
        wrappedRequest.setContent(encodedProxyRequest)
        wrappedRequest
      }
      case buffer: ChannelBuffer ⇒
        val wrappedRequest = createWrappedRequest
        if (HttpMethod.CONNECT.getName.getBytes(Utils.UTF8).length == buffer.readableBytes() &&
          HttpMethod.CONNECT.getName == IOUtils.toString(new ChannelBufferInputStream(ChannelBuffers.copiedBuffer(buffer)))) {
          wrappedRequest.setHeader("proxyRequestMethod", HttpMethod.CONNECT)
        } else {
          wrappedRequest.setHeader("proxyRequestMethod", "HTTPS-DATA-TRANSFER")
        }
        wrappedRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, buffer.readableBytes().toString)
        wrappedRequest.setContent(buffer)

        wrappedRequest
      case e ⇒ e
    }
    super.encode(ctx, channel, toBeSentMessage)
  }

  def createWrappedRequest = {

    val wrappedRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/proxy")
    wrappedRequest.setHeader(HttpHeaders.Names.HOST, connectHost.host.host)
    wrappedRequest.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE)
    wrappedRequest.addHeader(HttpHeaders.Names.ACCEPT, "application/octet-stream")
    wrappedRequest.addHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    wrappedRequest.setHeader(HttpHeaders.Names.USER_AGENT, "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:22.0) Gecko/20100101 Firefox/22.0")

    browserChannel.getAttachment match {
      case Some(jsessionid) if jsessionid.isInstanceOf[Cookie] ⇒ {
        val encoder = new CookieEncoder(false)
        encoder.addCookie(jsessionid.asInstanceOf[Cookie])
        wrappedRequest.setHeader(HttpHeaders.Names.COOKIE, encoder.encode())
      }
      case _ ⇒
    }
    wrappedRequest.setHeader("proxyHost", proxyHost.toString)
    wrappedRequest
  }
}

