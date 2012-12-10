package com.lifecosys.toolkit.proxy

import io.netty.logging.{Slf4JLoggerFactory, InternalLogger, Log4JLoggerFactory, InternalLoggerFactory}
import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.channel.socket.nio.{NioSocketChannel, NioServerSocketChannel, NioEventLoopGroup}
import java.net.InetSocketAddress
import scala.collection.mutable.MutableList
import io.netty.channel._
import io.netty.handler.codec.http._
import collection.mutable
import io.netty.channel.socket.SocketChannel
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.handler.logging.{LogLevel, LoggingHandler}
import Proxy._
import org.slf4j.LoggerFactory
import org.apache.commons.lang.StringUtils
import java.util.regex.Pattern
import io.netty.buffer.{ByteBufUtil, Unpooled, ByteBuf}
import scala.Predef._

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


  implicit def channelPipelineInitializer(f: ChannelPipeline => Any): ChannelInitializer[SocketChannel] = {
    new ChannelInitializer[SocketChannel] {
      def initChannel(ch: SocketChannel) {
        f(ch.pipeline())

        // Uncomment the following line if you want HTTPS
        //SSLEngine engine = SecureChatSslContextFactory.getServerContext().createSSLEngine();
        //engine.setUseClientMode(false);
        //clientToProxyPipeline.addLast("ssl", new SslHandler(engine));
        //        clientToProxyPipeline.addLast("decoder", new HttpRequestDecoder());
        //        clientToProxyPipeline.addLast("aggregator", new HttpChunkAggregator(65536));
        //        clientToProxyPipeline.addLast("encoder", new HttpResponseEncoder());
        //        clientToProxyPipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
        //
        //        clientToProxyPipeline.addLast("proxyHandler", new ProxyHandler);

      }
    }
  }


  class Proxy(port: Int) {
    implicit val chainProxies = MutableList[InetSocketAddress]()

    def clientToProxyPipeline = (pipeline: ChannelPipeline) => {
      // Uncomment the following line if you want HTTPS
      //SSLEngine engine = SecureChatSslContextFactory.getServerContext().createSSLEngine();
      //engine.setUseClientMode(false);
      //clientToProxyPipeline.addLast("ssl", new SslHandler(engine));
      pipeline.addLast("logger", new LoggingHandler(LogLevel.DEBUG));
      pipeline.addLast("decoder", new HttpRequestDecoder());
//      pipeline.addLast("aggregator", new HttpChunkAggregator(65536));
      pipeline.addLast("encoder", new HttpResponseEncoder());
//      pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
      pipeline.addLast("proxyHandler", new ProxyHandler);
    }


    val serverBootstrap = new ServerBootstrap

    def shutdown = serverBootstrap shutdown

    def start = {
      logger.info("Starting proxy server on " + port)
      serverBootstrap.group(new NioEventLoopGroup, new NioEventLoopGroup).channel(classOf[NioServerSocketChannel])
      serverBootstrap.localAddress(port).childHandler(clientToProxyPipeline)
//              .option(ChannelOption.CONNECT_TIMEOUT_MILLIS.asInstanceOf[ChannelOption[Any]], 40 * 1000)
//              .option(ChannelOption.SO_KEEPALIVE.asInstanceOf[ChannelOption[Any]], true)
//              .childOption(ChannelOption.TCP_NODELAY.asInstanceOf[ChannelOption[Any]], true)
//              .childOption(ChannelOption.SO_KEEPALIVE.asInstanceOf[ChannelOption[Any]], true)
      serverBootstrap.bind()

      Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
        def run {
          logger.info("Shutdown proxy server now.............")
          serverBootstrap shutdown
        }
      }))
      logger.info("Proxy server started on " + port)
    }

  }


  class ProxyHandler(implicit chainProxies: MutableList[InetSocketAddress]) extends ChannelInboundMessageHandlerAdapter[HttpRequest] {

    val hostToChannelFuture = mutable.Map[InetSocketAddress, ChannelFuture]()

    def messageReceived(ctx: ChannelHandlerContext, msg: HttpRequest) {
      logger.debug("Receive request: {} {}", msg.getMethod, msg.getUri)
      logger.debug("Receive request: {}", ctx.channel())
      val browserToProxyChannel = ctx.channel
      val host = chainProxies.get(0).getOrElse(parseHostAndPort(msg.getUri))
      msg match {
        case request: HttpRequest if HttpMethod.CONNECT == request.getMethod => {
          // Start the connection attempt.

          hostToChannelFuture.get(host) match {

            case Some(channelFuture) => {
              val statusLine: String = "HTTP/1.1 200 Connection established\r\n" + "Connection: Keep-Alive\r\n" + "Proxy-Connection: Keep-Alive\r\n\r\n"
              val buf = Unpooled.copiedBuffer("HTTP/1.1 200 Connection established\r\n\r\n".getBytes("UTF-8"))
              browserToProxyChannel.write(buf)
//                            ctx.readable(true)
            }
            case None => {
              val b: Bootstrap = new Bootstrap
              logger.debug("Starting new connection to: {}", host)

              b.group(browserToProxyChannel.eventLoop).channel(classOf[NioSocketChannel]).remoteAddress(host)
//                      .option(ChannelOption.TCP_NODELAY.asInstanceOf[ChannelOption[Any]], true)
                      .option(ChannelOption.CONNECT_TIMEOUT_MILLIS.asInstanceOf[ChannelOption[Any]], 4000 * 1000)
                      .option(ChannelOption.SO_KEEPALIVE.asInstanceOf[ChannelOption[Any]], true)
                      .handler {
                pipeline: ChannelPipeline => {
                  pipeline.addLast("logger", new LoggingHandler(LogLevel.DEBUG))
                  pipeline.addLast("connectionHandler", new ConnectionRequestHandler(browserToProxyChannel))
                }
              }
              //        cb.setOption("connectTimeoutMillis", 40 * 1000)


              val connectFuture: ChannelFuture = b.connect
              connectFuture.addListener(new ChannelFutureListener {
                def operationComplete(future: ChannelFuture) {
                  if (future.isSuccess) {

                    //                    ctx.readable(false)
                    val pipeline = browserToProxyChannel.pipeline()
                    import scala.collection.JavaConverters._
                    pipeline.names().asScala.filter(_ != "logger").foreach(pipeline.remove(_))
                    pipeline.addLast("connectionHandler", new ConnectionRequestHandler(connectFuture.channel))
                    val statusLine: String = "HTTP/1.1 200 Connection established\r\n" + "Connection: Keep-Alive\r\n" + "Proxy-Connection: Keep-Alive\r\n\r\n"
                    val buf = Unpooled.copiedBuffer(statusLine.getBytes("UTF-8"))
                    browserToProxyChannel.write(buf)
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

//          hostToChannelFuture.get(host) match {
//
//            case Some(channelFuture) => {
//              channelFuture.channel().write(request)
//            }
//            case None => {
//              //              ctx.readable(false)
//
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
//
//              val connectFuture: ChannelFuture = b.connect
//              connectFuture.addListener(new ChannelFutureListener {
//                def operationComplete(future: ChannelFuture) {
//                  if (future.isSuccess) {
//
//                    val writeFuture: ChannelFuture = connectFuture.channel().write(request)
//                    writeFuture.addListener(new ChannelFutureListener {
//                      def operationComplete(future: ChannelFuture) {
//                        logger.info("Write request to remote server completed.")
//                      }
//                    })
//                    //                    ctx.readable(true)
//                  }
//                  else {
//                    logger.info("Close browser connection...")
//                    browserToProxyChannel.close
//                  }
//                }
//              })
//            }
//          }
        }
      }

    }


    //  def messageReceived(clientToProxyContext: ChannelHandlerContext, clientToProxyMessage: HttpRequest)
    //  {
    //    logger.info("Received request..............")
    //    logger.info("################################")
    //    logger.info(clientToProxyMessage.toString)
    //
    //
    //
    //    val bootstrap: Bootstrap = new Bootstrap
    //
    //    bootstrap.group(new NioEventLoopGroup).channel(classOf[NioSocketChannel])
    //
    //    implicit  val ctpc=clientToProxyContext
    //    implicit  val ctpm=clientToProxyMessage
    //    bootstrap.handler(proxyToServerPipeline(clientToProxyContext,clientToProxyMessage))
    //
    //
    //    val host: InetSocketAddress = getHost(clientToProxyMessage)
    //
    //    bootstrap.remoteAddress(host)
    //    val ch: Channel = bootstrap.connect.channel
    //    ch.closeFuture().addListener(ChannelFutureListener.CLOSE)
    //
    //
    //    //
    //    //    val response = new DefaultHttpResponse(HTTP_1_1, OK)
    //    //    clientToProxyContext.write(response).addListener(ChannelFutureListener.CLOSE)
    //  }


    def getHost(clientToProxyMessage: HttpRequest)(implicit chainProxies: mutable.MutableList[InetSocketAddress]): InetSocketAddress = {
      chainProxies.get(0).getOrElse(clientToProxyMessage.getHeader(HttpHeaders.Names.HOST).split(":") match {
        case Array(host) => new InetSocketAddress(host, 80)
        case Array(host, port) if port.forall(_.isDigit) => new InetSocketAddress(host, port.toInt)
        case _ => sys.error("Invalid host")
      })
    }


    def proxyToServerPipeline(browserToProxyChannel: Channel) = (pipeline: ChannelPipeline) => {
      // Create a default clientToProxyPipeline implementation.
      pipeline.addLast("log", new LoggingHandler(LogLevel.INFO))
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
    }


    //    def remoteHandlerInitializer(ctx: ChannelHandlerContext, msg: HttpRequest) = new ProxyToServerHandlerInitializer(ctx, msg)
  }


  class HttpRelayingHandler(val browserToProxyChannel: Channel) extends ChannelInboundMessageHandlerAdapter[Any] {


    val logger = InternalLoggerFactory.getInstance(getClass)


    override def channelActive(ctx: ChannelHandlerContext) {
      super.channelActive(ctx)
    }


    def messageReceived(ctx: ChannelHandlerContext, msg: Any) {
      logger.info("=========================")
      logger.info(msg.toString)
      if (msg.isInstanceOf[DefaultHttpResponse]) {
        logger.info(msg.asInstanceOf[DefaultHttpResponse].getContent.toString)
      }
      browserToProxyChannel.write(msg)
    }
  }


  class ConnectionRequestHandler(relayChannel: Channel) extends ChannelInboundByteHandlerAdapter {


    def inboundBufferUpdated(ctx: ChannelHandlerContext, in: ByteBuf) {
      logger.debug("inboundBufferUpdated: relayChannel {} currentChannel {}", relayChannel, ctx.channel)
//      logger.debug("Received: {} \n {}",relayChannel.isActive ,ByteBufUtil.hexDump(in) )
      try{
        relayChannel.write(in).sync()
      }catch {
        case e => e.printStackTrace()
      }
      logger.debug("Finished writing data on CONNECT channel")
//              .addListener(new ChannelFutureListener {
//        def operationComplete(future: ChannelFuture) {
//          logger.debug("Finished writing data on CONNECT channel")
//        }
//      })
    }


    override def channelInactive(ctx: ChannelHandlerContext) {
      logger.info("Got closed event on connection: {}", ctx.channel())
      if (relayChannel.isActive) {
        logger.info("Close relay connection: {}", relayChannel)
        relayChannel.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
      }
    }




    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
      logger.info("Caught exception on connection: " + ctx.channel, cause)
      if (relayChannel.isActive) {
        relayChannel.write(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
      }
    }


  }


  class ProxyHandlerInitializer(implicit chainProxies: MutableList[InetSocketAddress]) extends ChannelInitializer[SocketChannel] {
    def initChannel(ch: SocketChannel) {
      val pipeline = ch.pipeline();

      // Uncomment the following line if you want HTTPS
      //SSLEngine engine = SecureChatSslContextFactory.getServerContext().createSSLEngine();
      //engine.setUseClientMode(false);
      //clientToProxyPipeline.addLast("ssl", new SslHandler(engine));

      pipeline.addLast("decoder", new HttpRequestDecoder());
      pipeline.addLast("aggregator", new HttpChunkAggregator(65536));
      pipeline.addLast("encoder", new HttpResponseEncoder());
      pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());

      pipeline.addLast("proxyHandler", new ProxyHandler);

    }
  }


  class ProxyToServerHandlerInitializer(val clientToProxyContext: ChannelHandlerContext, val clientToProxyMessage: HttpRequest) extends ChannelInitializer[SocketChannel] {
    def initChannel(ch: SocketChannel) {
      // Create a default clientToProxyPipeline implementation.
      val p: ChannelPipeline = ch.pipeline
      p.addLast("log", new LoggingHandler(LogLevel.INFO))
      // Enable HTTPS if necessary.
      //          if (ssl) {
      //            val engine: SSLEngine = SecureChatSslContextFactory.getClientContext.createSSLEngine
      //            engine.setUseClientMode(true)
      //            p.addLast("ssl", new SslHandler(engine))
      //          }
      p.addLast("codec", new HttpClientCodec)
      // Remove the following line if you don't want automatic content decompression.
      p.addLast("inflater", new HttpContentDecompressor)
      // Uncomment the following line if you don't want to handle HttpChunks.
      p.addLast("aggregator", new HttpChunkAggregator(1048576));
      p.addLast("proxyToServerHandler", new HttpRelayingHandler(clientToProxyContext.channel()))
      //          p.addLast("outboundHandler", new ChannelOutboundMessageHandlerAdapter[Object]{
      //            override def newOutboundBuffer(clientToProxyContext: ChannelHandlerContext): MessageBuf[Object] = {
      //
      //              super.newOutboundBuffer(clientToProxyContext)
      //
      //            }
      //
      //
      //            override def flush(clientToProxyContext: ChannelHandlerContext, future: ChannelFuture)
      //            {
      //              println("-------------------------------")
      //              clientToProxyContext.flush(future)
      //            }
      //
      //            override def freeOutboundBuffer(clientToProxyContext: ChannelHandlerContext, buf: ChannelBuf)
      //            {
      //              super.freeOutboundBuffer(clientToProxyContext, buf)
      //            }
      //          })
    }


  }


}