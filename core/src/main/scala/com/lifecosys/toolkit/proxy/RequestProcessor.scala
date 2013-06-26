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
import org.jboss.netty.buffer.{ ChannelBuffer, ChannelBuffers }
import org.jboss.netty.handler.codec.compression.{ ZlibEncoder, ZlibDecoder }
import org.jboss.netty.handler.codec.serialization.{ ClassResolvers, ObjectDecoder, ObjectEncoder }
import org.jboss.netty.handler.codec.oneone.{ OneToOneDecoder, OneToOneEncoder }
import org.littleshoot.proxy.ProxyUtils

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
    proxyToServerBootstrap.setOption("connectTimeoutMillis", 1200 * 1000)
    proxyToServerBootstrap
  }
}

class DefaultRequestProcessor(request: HttpRequest, browserToProxyChannelContext: ChannelHandlerContext)(implicit proxyConfig: ProxyConfig) extends RequestProcessor {

  override val httpRequest: HttpRequest = request
  override val browserToProxyContext = browserToProxyChannelContext

  val proxyHost = proxyConfig.getChainProxyManager.getConnectHost(httpRequest.getUri).get
  val browserToProxyChannel = browserToProxyContext.getChannel

  //Can't play online video since we send the full url for http request,
  // Exactly, we need use the relative url to access the remote server.
  if (!proxyHost.needForward) httpRequest.setUri(Utils.stripHost(httpRequest.getUri))

  def process {
    logger.debug("Process request with %s".format((proxyHost.host, proxyHost.needForward)))
    hostToChannelFuture.remove(proxyHost.host) match {
      case Some(channel) if channel.isConnected ⇒ {
        logger.error("###########Use existed Proxy to server conntection: {}################## Size {}##################", channel, hostToChannelFuture.size)
        channel.write(httpRequest)
      }
      case _ ⇒ {
        browserToProxyChannel.setReadable(false)
        val proxyToServerBootstrap = newClientBootstrap
        proxyToServerBootstrap.setFactory(proxyConfig.clientSocketChannelFactory)
        proxyToServerBootstrap.setPipelineFactory(proxyToServerPipeline)
        proxyToServerBootstrap.connect(proxyHost.host.socketAddress).addListener(connectProcess _)
      }
    }
  }

  def connectProcess(future: ChannelFuture) {
    browserToProxyChannel.setReadable(true)

    if (future.isSuccess) {
      //                  hostToChannelFuture.put(proxyHost.host, future.getChannel)
      future.getChannel().write(httpRequest).addListener {
        future: ChannelFuture ⇒ logger.debug("Write request to remote server %s completed.".format(future.getChannel))
      }
    } else {
      logger.debug("Close browser connection...")
      browserToProxyChannel.close()
    }
  }

  def proxyToServerPipeline = (pipeline: ChannelPipeline) ⇒ {
    //pipeline.addLast("logger", new LoggingHandler(proxyConfig.loggerLevel))
    if (proxyHost.needForward && proxyConfig.proxyToServerSSLEnable) {
      val engine = proxyConfig.clientSSLContext.createSSLEngine
      engine.setUseClientMode(true)
      pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
    }
    if (proxyHost.needForward) {
      pipeline.addLast("proxyServerToRemote-inflater", new IgnoreEmptyBufferZlibDecoder)
      pipeline.addLast("proxyServerToRemote-objectDecoder", new ObjectDecoder(ClassResolvers.weakCachingResolver(null)))
      pipeline.addLast("proxyServerToRemote-decrypt", new DecryptDecoder)

      pipeline.addLast("proxyServerToRemote-deflater", new IgnoreEmptyBufferZlibEncoder)
      pipeline.addLast("proxyServerToRemote-objectEncoder", new ObjectEncoder)
      pipeline.addLast("proxyServerToRemote-encrypt", new EncryptEncoder)
    }
    pipeline.addLast("proxyServerToRemote-codec", new HttpClientCodec(8192 * 2, 8192 * 4, 8192 * 4))
    pipeline.addLast("proxyServerToRemote-innerHttpChunkAggregator", new InnerHttpChunkAggregator())
    pipeline.addLast("proxyServerToRemote-idle", new IdleStateHandler(timer, 0, 0, 120))
    pipeline.addLast("proxyServerToRemote-idleAware", new IdleStateAwareChannelHandler {
      override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
        logger.debug("Channel idle........%s".format(e.getChannel))
        Utils.closeChannel(e.getChannel)
      }
    })
    pipeline.addLast("proxyServerToRemote-proxyToServerHandler", new HttpRelayingHandler(new ChannelResponder(browserToProxyChannel)))
  }

}

class ConnectionRequestProcessor(request: HttpRequest, browserToProxyChannelContext: ChannelHandlerContext)(implicit proxyConfig: ProxyConfig) extends RequestProcessor {
  override val httpRequest: HttpRequest = request
  val browserToProxyContext = browserToProxyChannelContext

  val proxyHost = proxyConfig.getChainProxyManager.getConnectHost(httpRequest.getUri).get
  val browserToProxyChannel = browserToProxyContext.getChannel

  def process {
    logger.debug("Process request with %s".format((proxyHost.host, proxyHost.needForward)))
    hostToChannelFuture.get(proxyHost.host) match {
      case Some(channel) if channel.isConnected ⇒ browserToProxyChannel.write(ChannelBuffers.copiedBuffer(Utils.connectProxyResponse.getBytes("UTF-8")))
      case None ⇒ {
        browserToProxyChannel.setReadable(false)
        logger.debug("Starting new connection to: %s".format(proxyHost.host))
        createProxyToServerBootstrap.connect(proxyHost.host.socketAddress).addListener(connectComplete _)
      }
    }
  }

  def createProxyToServerBootstrap = {
    val proxyToServerBootstrap = newClientBootstrap
    proxyToServerBootstrap.setFactory(proxyConfig.clientSocketChannelFactory)
    proxyToServerBootstrap.setPipelineFactory {
      pipeline: ChannelPipeline ⇒

        //pipeline.addLast("logger", new LoggingHandler(proxyConfig.loggerLevel))

        if (proxyHost.needForward && proxyConfig.proxyToServerSSLEnable) {
          val engine = proxyConfig.clientSSLContext.createSSLEngine
          engine.setUseClientMode(true)
          pipeline.addLast("proxyServerToRemote-ssl", new SslHandler(engine))
        }

        if (proxyHost.needForward) {
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

        pipeline.addLast("proxyServerToRemote-connectionHandler", new ConnectionRequestHandler(browserToProxyChannel))
    }
    proxyToServerBootstrap
  }

  def connectComplete(future: ChannelFuture): Unit = {
    logger.debug("Connection successful: %s".format(future.getChannel))

    if (!future.isSuccess) {
      logger.debug("Close browser connection...")
      Utils.closeChannel(browserToProxyChannel)
      return
    }

    val pipeline = browserToProxyChannel.getPipeline
    //Remove codec related handle for connect request, it's necessary for HTTPS.
    List("proxyServer-encoder", "proxyServer-decoder", "proxyServer-proxyHandler").foreach(pipeline remove _)
    pipeline.addLast("proxyServer-connectionHandler", new ConnectionRequestHandler(future.getChannel))

    def sendRequestToChainedProxy {
      future.getChannel.getPipeline.addBefore("proxyServerToRemote-connectionHandler", "proxyServerToRemote-encoder", new HttpRequestEncoder)
      future.getChannel.write(httpRequest).addListener {
        writeFuture: ChannelFuture ⇒
          {
            writeFuture.getChannel.getPipeline.remove("proxyServerToRemote-encoder")
            logger.debug("Finished write request to %s\n %s ".format(future.getChannel, httpRequest))
          }
      }
    }

    if (proxyHost.needForward)
      sendRequestToChainedProxy
    else browserToProxyChannel.write(ChannelBuffers.copiedBuffer(Utils.connectProxyResponse.getBytes("UTF-8"))).addListener {
      future: ChannelFuture ⇒ logger.debug("Finished write request to %s \n %s ".format(future.getChannel, Utils.connectProxyResponse))
    }

    browserToProxyChannel.setReadable(true)
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

//
//class WebProxyResponseEncoder extends OneToOneEncoder {
//   def encode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) =msg match {
//     case request: HttpRequest => {
//       val encodedProxyRequest=new HttpRequestEncoder().encode(ctx, channel, ProxyUtils.copyHttpRequest(request, false)).asInstanceOf[ChannelBuffer]
//       val toSendRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/proxy")
//       toSendRequest.setHeader(HttpHeaders.Names.HOST, host.host.host)
//       toSendRequest.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE)
//       toSendRequest.addHeader(HttpHeaders.Names.ACCEPT, "application/octet-stream")
//       toSendRequest.addHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
//       toSendRequest.setHeader(HttpHeaders.Names.USER_AGENT, "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:22.0) Gecko/20100101 Firefox/22.0")
//       toSendRequest.setHeader("proxyHost", Host(request.getUri).toString)
//       toSendRequest.setContent(encodedProxyRequest)
//       super.encode(ctx, channel, toSendRequest)
//
//     }
//   }
//
//     if (msg.isInstanceOf[ChannelBuffer])
//    EncryptDataWrapper(Utils.cryptor.encrypt(ChannelBuffers.copiedBuffer(msg.asInstanceOf[ChannelBuffer]).array()))
//  else
//    msg
//}
//
//class WebProxyRequestEncoder extends OneToOneDecoder {
//  def decode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = msg match {
//    case EncryptDataWrapper(data) ⇒ ChannelBuffers.copiedBuffer(Utils.cryptor.decrypt(data))
//    case _                        ⇒ msg
//  }
//
//  override def encode(ctx: ChannelHandlerContext, channel: Channel, msg: Any): AnyRef = {
//    msg match {
//      case request: HttpRequest  ⇒{
//        val encodedProxyRequest=super.encode(ctx, channel, ProxyUtils.copyHttpRequest(request, false)).asInstanceOf[ChannelBuffer]
//        val toSendRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/proxy")
//        toSendRequest.setHeader(HttpHeaders.Names.HOST, host.host.host)
//        toSendRequest.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE)
//        toSendRequest.addHeader(HttpHeaders.Names.ACCEPT, "application/octet-stream")
//        toSendRequest.addHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
//        toSendRequest.setHeader(HttpHeaders.Names.USER_AGENT, "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:22.0) Gecko/20100101 Firefox/22.0")
//        toSendRequest.setHeader("proxyHost", Host(request.getUri).toString)
//        toSendRequest.setContent(encodedProxyRequest)
//        super.encode(ctx, channel, toSendRequest)
//      }
//      case _ => throw new RuntimeException("Unsupported message, you can't encode other message except HttpRequest.")
//      //                      case chunk: HttpChunk  ⇒super.encode(ctx, channel, chunk).asInstanceOf[ChannelBuffer]
//      //                      case _ ⇒{
//      //                        val encode = classOf[ObjectEncoder].getSuperclass.getDeclaredMethods.filter(_.getName == "encode")(0)
//      //                        encode.setAccessible(true)
//      //                        encode.invoke(new ObjectEncoder(), null, channel, msg.asInstanceOf[Object]).asInstanceOf[ChannelBuffer]
//      //                      }
//    }
//  }
//
//
//}

