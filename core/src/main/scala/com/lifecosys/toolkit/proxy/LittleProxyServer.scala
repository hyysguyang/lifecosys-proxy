package com.lifecosys.toolkit.proxy

import org.littleshoot.proxy._
import org.jboss.netty.channel._
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.handler.ssl.SslHandler
import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.channel.socket.ClientSocketChannelFactory
import org.jboss.netty.util.Timer
import org.littleshoot.proxy.{ ChainProxyManager ⇒ LittleChainProxyManager }
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel.group.ChannelGroup
import scala.Some
import org.jboss.netty.buffer.{ ChannelBufferInputStream, ChannelBuffer, ChannelBuffers, ChannelBufferOutputStream }
import org.jboss.netty.handler.codec.serialization.{ ObjectEncoder, ObjectEncoderOutputStream }
import org.jboss.netty.logging.InternalLogLevel
import org.jboss.netty.handler.logging.LoggingHandler
import org.jboss.netty.handler.codec.http.HttpMessageDecoder.State
import org.apache.commons.io.IOUtils
import org.jboss.netty.handler.codec.oneone.OneToOneDecoder
import com.typesafe.scalalogging.slf4j.Logging

/**
 *
 *
 * @author Young Gu
 * @version 1.0 6/6/13 3:46 PM
 */
class LittleProxyServer(port: Int)(implicit proxyConfig: ProxyConfig)
    extends org.littleshoot.proxy.DefaultHttpProxyServer(port) with Logging {
  val chainProxyManager = proxyConfig.getChainProxyManager

  val littleChainProxyManager = if (proxyConfig.chainProxies.isEmpty) null else new LittleChainProxyManager() {
    def getChainProxy(request: HttpRequest): String = {
      chainProxyManager.getConnectHost(request.getUri).get.host.toString
    }

    def onCommunicationError(hostAndPort: String) = chainProxyManager.connectFailed(hostAndPort)
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
      /**
       * Customized LittleProxy
       *
       * Adjust the created handle.
       *
       * @return
       */
      def getPipeline: ChannelPipeline = {
        val pipeline = factory.getPipeline
        if (proxyConfig.serverSSLEnable) {
          val engine = proxyConfig.serverSSLContext.createSSLEngine
          engine.setUseClientMode(false)
          engine.setNeedClientAuth(true)
          pipeline.addFirst("proxyServer-ssl", new SslHandler(engine))
        }

        val httpRequestHandler = new HttpRequestHandler(ProxyUtils.loadCacheManager, authenticationManager, allChannels, littleChainProxyManager, relayPipelineFactoryFactory, clientChannelFactory) {

          /**
           * Customized LittleProxy
           *
           * Check if we need forward the connect request to upstream proxy server.
           *
           * @param httpRequest
           * @return
           */
          override def needForward(httpRequest: HttpRequest): Boolean = chainProxyManager.getConnectHost(httpRequest.getUri).get.needForward

          /**
           * Customized LittleProxy
           *
           * Adjust the handle before connect to remote host.
           *
           * @param clientBootstrap
           * @param request
           */
          override def preConnect(clientBootstrap: ClientBootstrap, request: HttpRequest) {
            val factory: ChannelPipelineFactory = clientBootstrap.getPipelineFactory
            clientBootstrap.setPipelineFactory(new ChannelPipelineFactory {
              def getPipeline = {
                val pipeline: ChannelPipeline = factory.getPipeline
                if (chainProxyManager.getConnectHost(request.getUri).get.needForward && proxyConfig.proxyToServerSSLEnable) {
                  val engine = proxyConfig.clientSSLContext.createSSLEngine
                  engine.setUseClientMode(true)
                  pipeline.addFirst("proxyServerToRemote-ssl", new SslHandler(engine))
                }
                //                pipeline.addFirst("logger", new LoggingHandler(InternalLogLevel.WARN))

                //                if (!chainProxyManager.getConnectHost(request.getUri).get.needForward) {
                //                  Some(pipeline.get(classOf[ProxyHttpRequestEncoder])).foreach(_.keepProxyFormat = false)
                //                }

                val host = chainProxyManager.getConnectHost(request.getUri).get
                //                pipeline.replace(classOf[ProxyHttpRequestEncoder], "proxyEncoder",new ProxyHttpRequestEncoder(pipeline.get(classOf[org.littleshoot.proxy.NetHttpResponseRelayingHandler]), null, host.needForward) )

                val webProxyRequestEncoder = new ProxyHttpRequestEncoder(pipeline.get(classOf[org.littleshoot.proxy.HttpRelayingHandler]), null, false) {
                  override def encode(ctx: ChannelHandlerContext, channel: Channel, msg: Any): AnyRef = {
                    //                    logger.warn("&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&& " + msg)
                    //                    val handler = pipeline.get(classOf[org.littleshoot.proxy.NetHttpResponseRelayingHandler])
                    //                    val proxyChannel: Channel = handler.getBrowserToProxyChannel()
                    //                    proxyChannel.getPipeline.replace(classOf[ProxyHttpResponseEncoder], "encoder", new HttpResponseEncoder {
                    //                      override def encode(ctx: ChannelHandlerContext, channel: Channel, msg: Any): AnyRef = msg match {
                    //                        case proxyResponse: ProxyHttpResponse ⇒ proxyResponse.getHttpResponse().getContent
                    //                        case _                                ⇒ super.encode(ctx, channel, msg)
                    //                      }
                    //                    })
                    msg match {
                      case request: HttpRequest ⇒ {
                        //                        handler.requestEncoded(request)
                        val encodedProxyRequest = super.encode(ctx, channel, ProxyUtils.copyHttpRequest(request, false)).asInstanceOf[ChannelBuffer]

                        //                        println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$")
                        println(IOUtils.toString(new ChannelBufferInputStream(ChannelBuffers.copiedBuffer(encodedProxyRequest))))
                        println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$")
                        val toSendRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/proxy")
                        toSendRequest.setHeader(HttpHeaders.Names.HOST, host.host.host)
                        toSendRequest.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE)
                        toSendRequest.addHeader(HttpHeaders.Names.ACCEPT, "application/octet-stream")
                        toSendRequest.addHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
                        toSendRequest.setHeader(HttpHeaders.Names.USER_AGENT, "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:22.0) Gecko/20100101 Firefox/22.0")
                        toSendRequest.setHeader("proxyHost", Host(request.getUri).toString)
                        toSendRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, encodedProxyRequest.readableBytes().toString)
                        toSendRequest.setContent(encodedProxyRequest)
                        //                        toSendRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, "aaaaaaaaaaaaaaaaaaaaaaa".getBytes.length.toString)
                        //                        toSendRequest.setContent(ChannelBuffers.copiedBuffer("aaaaaaaaaaaaaaaaaaaaaaa".getBytes))
                        super.encode(ctx, channel, toSendRequest)
                      }
                      case chunk: HttpChunk ⇒ {
                        println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$")
                        super.encode(ctx, channel, chunk)
                      }
                      case e ⇒ super.encode(ctx, channel, e)
                      //                      case chunk: HttpChunk  ⇒super.encode(ctx, channel, chunk).asInstanceOf[ChannelBuffer]
                      //                      case _ ⇒{
                      //                        val encode = classOf[ObjectEncoder].getSuperclass.getDeclaredMethods.filter(_.getName == "encode")(0)
                      //                        encode.setAccessible(true)
                      //                        encode.invoke(new ObjectEncoder(), null, channel, msg.asInstanceOf[Object]).asInstanceOf[ChannelBuffer]
                      //                      }
                    }
                  }
                }

                if (pipeline.get(classOf[ProxyHttpRequestEncoder]) != null) {
                  pipeline.replace(classOf[ProxyHttpRequestEncoder], "proxyEncoder", webProxyRequestEncoder)
                }

                val webProxyResponseDecoder = new OneToOneDecoder {
                  def decode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = msg match {
                    case response: HttpResponse ⇒ ChannelBuffers.copiedBuffer(response.getContent)
                    case _                      ⇒ msg
                  }
                }

                //                  override def decode(ctx: ChannelHandlerContext, channel: Channel, buffer: ChannelBuffer, state: State): AnyRef = {
                //                    val response = super.decode(ctx, channel, buffer, state).asInstanceOf[HttpResponse]
                //                    super.decode(ctx, channel, response.getContent, state)
                //                  }
                //                }

                //                pipeline.addAfter("decoder", "proxyDecoder", webProxyResponseDecoder)
                //                pipeline.addAfter("proxyDecoder", "wrapperResponseDecoder", new HttpResponseDecoder(8192, 8192 * 2, 8192 * 2))

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

