package com.lifecosys.toolkit.proxy

import org.jboss.netty.logging.{InternalLogLevel, Slf4JLoggerFactory, InternalLoggerFactory}
import org.slf4j.LoggerFactory
import java.util.regex.Pattern
import org.apache.commons.lang.StringUtils
import java.net.InetSocketAddress
import org.jboss.netty.channel._
import collection.mutable
import org.jboss.netty.handler.logging.LoggingHandler
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.bootstrap.{ClientBootstrap, ServerBootstrap}
import socket.nio.{NioClientSocketChannelFactory, NioServerSocketChannelFactory}
import org.jboss.netty.buffer.{ChannelBuffer, ChannelBuffers}
import scala.Some
import scala.Some


/**
 * Created with IntelliJ IDEA.
 * User: young
 * Date: 12/2/12
 * Time: 1:44 AM
 * To change this template use File | Settings | File Templates.
 */


object ProxyServer {

  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  val logger = LoggerFactory.getLogger(getClass)

  def apply(port: Int): Proxy = new Proxy(port)

  private val HTTP_PREFIX: Pattern = Pattern.compile("http.*", Pattern.CASE_INSENSITIVE)
  private val HTTPS_PREFIX: Pattern = Pattern.compile("https.*", Pattern.CASE_INSENSITIVE)

  /**
   * Parses the host and port an HTTP request is being sent to.
   *
   * @param uri The URI.
   * @return The host and port string.
   */
  def parseHostAndPort(uri: String) = {
    var tempUri: String = null
    if (!HTTP_PREFIX.matcher(uri).matches) {
      tempUri = uri
    } else {
      tempUri = StringUtils.substringAfter(uri, "://")
    }
    var hostAndPort: String = null
    if (tempUri.contains("/")) {
      hostAndPort = tempUri.substring(0, tempUri.indexOf("/"))
    } else {
      hostAndPort = tempUri
    }


    if (hostAndPort.contains(":")) {
      val portString: String = StringUtils.substringAfter(hostAndPort, ":")
      new InetSocketAddress(StringUtils.substringBefore(hostAndPort, ":"), Integer.parseInt(portString))
    } else {
      new InetSocketAddress(hostAndPort, 80)
    }
  }

//
//  implicit def channelPipelineInitializer(f: ChannelPipeline => Any): ChannelInitializer[SocketChannel] = {
//    new ChannelInitializer[SocketChannel] {
//      def initChannel(ch: SocketChannel) {
//        f(ch.pipeline())
//      }
//    }
//  }


  class Proxy(port: Int) {
    implicit val chainProxies = mutable.MutableList[InetSocketAddress]()

    def clientToProxyPipeline = (pipeline: ChannelPipeline) => {
      // Uncomment the following line if you want HTTPS
      //SSLEngine engine = SecureChatSslContextFactory.getServerContext().createSSLEngine();
      //engine.setUseClientMode(false);
      //clientToProxyPipeline.addLast("ssl", new SslHandler(engine));
      pipeline.addLast("logger", new LoggingHandler(InternalLogLevel.DEBUG));
      pipeline.addLast("decoder", new HttpRequestDecoder());
//      pipeline.addLast("aggregator", new HttpChunkAggregator(65536));
      pipeline.addLast("encoder", new HttpResponseEncoder());
//      pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
      pipeline.addLast("proxyHandler", new ProxyHandler);
    }


    val serverBootstrap = new ServerBootstrap(new NioServerSocketChannelFactory())

    def shutdown ={} // serverBootstrap shutdown

    def start = {
      logger.info("Starting proxy server on " + port)
//      serverBootstrap.group(new NioEventLoopGroup, new NioEventLoopGroup).channel(classOf[NioServerSocketChannel])
//      serverBootstrap.localAddress(port).childHandler(clientToProxyPipeline)
//              .option(ChannelOption.CONNECT_TIMEOUT_MILLIS.asInstanceOf[ChannelOption[Any]], 4000 * 1000)
//              .option(ChannelOption.SO_KEEPALIVE.asInstanceOf[ChannelOption[Any]], true)
//              .childOption(ChannelOption.TCP_NODELAY.asInstanceOf[ChannelOption[Any]], true)
//              .childOption(ChannelOption.SO_KEEPALIVE.asInstanceOf[ChannelOption[Any]], true)
//              .childOption(ChannelOption.CONNECT_TIMEOUT_MILLIS.asInstanceOf[ChannelOption[Any]], 4000 * 1000)

      serverBootstrap.setPipelineFactory(new ChannelPipelineFactory{
        def getPipeline: ChannelPipeline = {
           val pipeline = Channels.pipeline();
          // Uncomment the following line if you want HTTPS
          //SSLEngine engine = SecureChatSslContextFactory.getServerContext().createSSLEngine();
          //engine.setUseClientMode(false);
          //clientToProxyPipeline.addLast("ssl", new SslHandler(engine));
          pipeline.addLast("logger", new LoggingHandler(InternalLogLevel.DEBUG));
          pipeline.addLast("decoder", new HttpRequestDecoder());
          //      pipeline.addLast("aggregator", new HttpChunkAggregator(65536));
          pipeline.addLast("encoder", new HttpResponseEncoder());
          //      pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
          pipeline.addLast("proxyHandler", new ProxyHandler);
          pipeline
        }
      })

      serverBootstrap.bind(new InetSocketAddress(port))

      Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
        def run {
          logger.info("Shutdown proxy server now.............")
//          serverBootstrap shutdown
        }
      }))
      logger.info("Proxy server started on " + port)
    }

  }


  class ProxyHandler(implicit chainProxies: mutable.MutableList[InetSocketAddress]) extends  SimpleChannelUpstreamHandler {

    val hostToChannelFuture = mutable.Map[InetSocketAddress, ChannelFuture]()



    override def messageReceived(ctx: ChannelHandlerContext, me: MessageEvent) {
      val msg=me.getMessage().asInstanceOf[HttpRequest]
      logger.debug("Receive request: {} {}", msg.getMethod, msg.getUri)
      logger.debug("Receive request: {}", ctx.getChannel())
      val browserToProxyChannel = ctx.getChannel
      val host = chainProxies.get(0).getOrElse(parseHostAndPort(msg.getUri))
      msg match {
        case request: HttpRequest if HttpMethod.CONNECT == request.getMethod => {
          // Start the connection attempt.

          hostToChannelFuture.get(host) match {

            case Some(channelFuture) => {
              val statusLine: String = "HTTP/1.1 200 Connection established\r\n" + "Connection: Keep-Alive\r\n" + "Proxy-Connection: Keep-Alive\r\n\r\n"
              val buf =ChannelBuffers.copiedBuffer("HTTP/1.1 200 Connection established\r\n\r\n".getBytes("UTF-8"))
              browserToProxyChannel.write(buf)
//                            ctx.readable(true)
            }
            case None => {

              ctx.getChannel.setReadable(false)
              val b = new ClientBootstrap(new NioClientSocketChannelFactory())
              b.setPipelineFactory(new ChannelPipelineFactory
              {
                def getPipeline: ChannelPipeline =
                {
                  val pipeline: ChannelPipeline = Channels.pipeline
                  pipeline.addLast("logger", new LoggingHandler(InternalLogLevel.DEBUG))
                  pipeline.addLast("handler", new ConnectionRequestHandler(browserToProxyChannel))
                  return pipeline
                }
              })


              b.setOption("connectTimeoutMillis", 40 * 1000)
              logger.debug("Starting new connection to: {}", host)

//              b.group(browserToProxyChannel.eventLoop).channel(classOf[NioSocketChannel]).remoteAddress(host)
////                      .option(ChannelOption.TCP_NODELAY.asInstanceOf[ChannelOption[Any]], true)
//                      .option(ChannelOption.CONNECT_TIMEOUT_MILLIS.asInstanceOf[ChannelOption[Any]], 4000 * 1000)
//                      .option(ChannelOption.SO_KEEPALIVE.asInstanceOf[ChannelOption[Any]], true)
//                      .handler {
//                pipeline: ChannelPipeline => {
//                  pipeline.addLast("logger", new LoggingHandler(LogLevel.DEBUG))
//                  pipeline.addLast("connectionHandler", new ConnectionRequestHandler(browserToProxyChannel))
//                }
//              }
              //        cb.setOption("connectTimeoutMillis", 40 * 1000)


              val connectFuture: ChannelFuture = b.connect(host)
              connectFuture.addListener(new ChannelFutureListener {
                def operationComplete(future: ChannelFuture) {
                  if (future.isSuccess) {

                    //                    ctx.readable(false)
                    val pipeline = browserToProxyChannel.getPipeline
                    while(pipeline.getLast!=null)(pipeline.removeLast())
//                    pipeline.names().asScala.filter(_ != "logger").foreach(pipeline.remove(_))
                    pipeline.addLast("connectionHandler", new ConnectionRequestHandler(connectFuture.getChannel))
                    val statusLine: String = "HTTP/1.1 200 Connection established\r\n" + "Connection: Keep-Alive\r\n" + "Proxy-Connection: Keep-Alive\r\n\r\n"
                    val buf = ChannelBuffers.copiedBuffer(statusLine.getBytes("UTF-8"))
                   val wf=browserToProxyChannel.write(buf)


                    wf.addListener(new ChannelFutureListener
                    {
                      def operationComplete(wcf: ChannelFuture)
                      {
                        logger.info("Finished write: " + wcf + " to: " + request.getMethod + " " + request.getUri)
                        ctx.getChannel.setReadable(true)
                      }
                    })
                    //                    ctx.readable(true)
                  }
                  else {
                    logger.info("Close browser connection...")
                    browserToProxyChannel.close
                  }
                }
              })
            }
          }


        }

        case request: HttpRequest => {

          hostToChannelFuture.get(host) match {

            case Some(channelFuture) => {
              channelFuture.getChannel().write(request)
            }
            case None => {
              //              ctx.readable(false)


              val b = new ClientBootstrap(new NioClientSocketChannelFactory())
              b.setPipelineFactory(new ChannelPipelineFactory
              {
                def getPipeline: ChannelPipeline =
                {
                  val pipeline: ChannelPipeline = Channels.pipeline
                        pipeline.addLast("log", new LoggingHandler(InternalLogLevel.DEBUG))
                        // Enable HTTPS if necessary.
                        //          if (ssl) {
                        //            val engine: SSLEngine = SecureChatSslContextFactory.getClientContext.createSSLEngine
                        //            engine.setUseClientMode(true)
                        //            pipeline.addLast("ssl", new SslHandler(engine))
                        //          }
                        pipeline.addLast("codec", new HttpClientCodec(8192, 8192 * 2, 8192 * 2))
                        // Remove the following line if you don't want automatic content decompression.
                        pipeline.addLast("inflater", new HttpContentDecompressor)
                        // Uncomment the following line if you don't want to handle HttpChunks.
                  //      pipeline.addLast("aggregator", new HttpChunkAggregator(1048576));
                        pipeline.addLast("proxyToServerHandler", new HttpRelayingHandler(browserToProxyChannel))
                  return pipeline
                }
              })


              b.setOption("connectTimeoutMillis", 40 * 1000)


//              // Start the connection attempt.
//              val b: Bootstrap = new Bootstrap
//              val host = chainProxies.get(0).getOrElse(parseHostAndPort(request.getUri))
//              logger.debug("Starting new connection to: {}", host)
//
//              b.group(browserToProxyChannel.eventLoop).channel(classOf[NioSocketChannel]).remoteAddress(host)
//                      .option(ChannelOption.TCP_NODELAY.asInstanceOf[ChannelOption[Any]], true)
//                      .option(ChannelOption.CONNECT_TIMEOUT_MILLIS.asInstanceOf[ChannelOption[Any]], 4000* 10000)
//                      .option(ChannelOption.SO_KEEPALIVE.asInstanceOf[ChannelOption[Any]], true)
//                      .handler(proxyToServerPipeline(browserToProxyChannel))
//              //        cb.setOption("connectTimeoutMillis", 40 * 1000)

              val connectFuture: ChannelFuture = b.connect(host)
              connectFuture.addListener(new ChannelFutureListener {
                def operationComplete(future: ChannelFuture) {
                  if (future.isSuccess) {

                    val writeFuture: ChannelFuture = connectFuture.getChannel().write(request)
                    writeFuture.addListener(new ChannelFutureListener {
                      def operationComplete(future: ChannelFuture) {
                        logger.info("Write request to remote server completed.")
                      }
                    })
                    //                    ctx.readable(true)
                  }
                  else {
                    logger.info("Close browser connection...")
                    browserToProxyChannel.close
                  }
                }
              })
            }
          }
        }
      }

    }


//    def proxyToServerPipeline(browserToProxyChannel: Channel) = (pipeline: ChannelPipeline) => {
//      // Create a default clientToProxyPipeline implementation.
//      pipeline.addLast("log", new LoggingHandler(LogLevel.INFO))
//      // Enable HTTPS if necessary.
//      //          if (ssl) {
//      //            val engine: SSLEngine = SecureChatSslContextFactory.getClientContext.createSSLEngine
//      //            engine.setUseClientMode(true)
//      //            pipeline.addLast("ssl", new SslHandler(engine))
//      //          }
//      pipeline.addLast("codec", new HttpClientCodec(8192, 8192 * 2, 8192 * 2))
//      // Remove the following line if you don't want automatic content decompression.
//      pipeline.addLast("inflater", new HttpContentDecompressor)
//      // Uncomment the following line if you don't want to handle HttpChunks.
////      pipeline.addLast("aggregator", new HttpChunkAggregator(1048576));
//      pipeline.addLast("proxyToServerHandler", new HttpRelayingHandler(browserToProxyChannel))
//    }


  }


  class HttpRelayingHandler(val browserToProxyChannel: Channel) extends SimpleChannelUpstreamHandler {
    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent){
      logger.info("=========================")
      logger.info(e.getMessage.toString)

      if (browserToProxyChannel.isConnected){
        browserToProxyChannel.write(e.getMessage)
      }else {
        if (e.getChannel.isConnected)
        {
          logger.info("Closing channel to remote server {}" ,e.getChannel)
          e.getChannel.close
        }
      }
    }
  }


  class ConnectionRequestHandler(relayChannel: Channel)  extends SimpleChannelUpstreamHandler
  {
    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent)
    {
      val msg: ChannelBuffer = e.getMessage.asInstanceOf[ChannelBuffer]

      relayChannel.write(msg)
//      if (relayChannel.isConnected)
//      {
//        val logListener: ChannelFutureListener = new ChannelFutureListener
//        {
//          def operationComplete(future: ChannelFuture)
//          {
//            logger.error("####################Finished writing data on CONNECT channel")
//          }
//        }
//        relayChannel.write(msg).addListener(logListener)
//      } else
//      {
//        logger.info("Channel not open. Connected? {}", relayChannel.isConnected)
//
//
//
//        if (e.getChannel.isConnected)
//        {
//          e.getChannel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
//        }
//      }
    }

    override def channelOpen(ctx: ChannelHandlerContext, cse: ChannelStateEvent)
    {
      val ch: Channel = cse.getChannel
      logger.info("New CONNECT channel opened from proxy to web: {}", ch)
    }

    override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent)
    {
      logger.info("Got closed event on proxy -> web connection: {}", e.getChannel)


      if (relayChannel.isConnected)
      {
        relayChannel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
      }

    }

    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent)
    {
      logger.info("Caught exception on proxy -> web connection: " + e.getChannel, e.getCause)

      if (e.getChannel.isConnected)
      {
        e.getChannel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
      }
    }

//    def inboundBufferUpdated(ctx: ChannelHandlerContext, in: ByteBuf) {
//      logger.debug("inboundBufferUpdated: relayChannel {} currentChannel {}", relayChannel, ctx.channel)
////      logger.debug("Received: {} \n {}",relayChannel.isActive ,ByteBufUtil.hexDump(in) )
//      try{
//        relayChannel.write(in).sync()
//      }catch {
//        case e => e.printStackTrace()
//      }
//      logger.debug("Finished writing data on CONNECT channel")
////              .addListener(new ChannelFutureListener {
////        def operationComplete(future: ChannelFuture) {
////          logger.debug("Finished writing data on CONNECT channel")
////        }
////      })
//    }
//
//
//    override def channelInactive(ctx: ChannelHandlerContext) {
//      logger.info("Got closed event on connection: {}", ctx.channel())
//      if (relayChannel.isActive) {
//        logger.info("Close relay connection: {}", relayChannel)
//        relayChannel.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
//      }
//    }
//
//
//
//
//    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
//      logger.info("Caught exception on connection: " + ctx.channel, cause)
//      if (relayChannel.isActive) {
//        relayChannel.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
//      }
//    }


  }
}