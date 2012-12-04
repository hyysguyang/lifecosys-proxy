package com.lifecosys.toolkit.proxy

import io.netty.logging.{InternalLogger, Log4JLoggerFactory, InternalLoggerFactory}
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

/**
 * Created with IntelliJ IDEA.
 * User: young
 * Date: 12/2/12
 * Time: 1:44 AM
 * To change this template use File | Settings | File Templates.
 */


object Proxy
{

  implicit def channelPipelineInitializer(f: ChannelPipeline => Any):ChannelInitializer[SocketChannel] =
  {
    new ChannelInitializer[SocketChannel]
    {
      def initChannel(ch: SocketChannel)
      {
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
}

class Proxy(port: Int)
{
 implicit val chainProxies = MutableList[InetSocketAddress]()

  def clientToProxyPipeline = (pipeline: ChannelPipeline) =>
  {
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



  InternalLoggerFactory.setDefaultFactory(new Log4JLoggerFactory)
  val logger = InternalLoggerFactory.getInstance(getClass)

  val serverBootstrap = new ServerBootstrap

  def shutdown = serverBootstrap shutdown

  def start =
  {
    logger.info("Starting proxy server on " + port)
    serverBootstrap.group(new NioEventLoopGroup, new NioEventLoopGroup).channel(classOf[NioServerSocketChannel])
    serverBootstrap.localAddress(port).childHandler(clientToProxyPipeline)
    serverBootstrap.bind()

    Runtime.getRuntime.addShutdownHook(new Thread(new Runnable
    {
      def run
      {
        logger.info("Shutdown proxy server now.............")
        serverBootstrap shutdown
      }
    }))
    logger.info("Proxy server started on " + port)
  }

}





class ProxyHandler(implicit chainProxies: MutableList[InetSocketAddress]) extends ChannelInboundMessageHandlerAdapter[HttpRequest]
{
  val logger = InternalLoggerFactory.getInstance(getClass)

  def messageReceived(clientToProxyContext: ChannelHandlerContext, clientToProxyMessage: HttpRequest)
  {
    logger.info("Received request..............")
    logger.info("################################")
    logger.info(clientToProxyMessage.toString)



    val bootstrap: Bootstrap = new Bootstrap

    bootstrap.group(new NioEventLoopGroup).channel(classOf[NioSocketChannel])

    implicit  val ctpc=clientToProxyContext
    implicit  val ctpm=clientToProxyMessage
    bootstrap.handler(proxyToServerPipeline(clientToProxyContext,clientToProxyMessage))


    val host: InetSocketAddress = getHost(clientToProxyMessage)

    bootstrap.remoteAddress(host)
    val ch: Channel = bootstrap.connect.channel
    ch.closeFuture().addListener(ChannelFutureListener.CLOSE)


    //
    //    val response = new DefaultHttpResponse(HTTP_1_1, OK)
    //    clientToProxyContext.write(response).addListener(ChannelFutureListener.CLOSE)
  }


  def getHost(clientToProxyMessage: HttpRequest)(implicit chainProxies: mutable.MutableList[InetSocketAddress]): InetSocketAddress =
  {
    chainProxies.get(0).getOrElse(clientToProxyMessage.getHeader(HttpHeaders.Names.HOST).split(":") match
    {
      case Array(host) => new InetSocketAddress(host, 80)
      case Array(host, port) if port.forall(_.isDigit) => new InetSocketAddress(host, port.toInt)
      case _ => sys.error("Invalid host")
    })
  }


  def proxyToServerPipeline(clientToProxyContext: ChannelHandlerContext, clientToProxyMessage: HttpRequest) = (pipeline: ChannelPipeline) =>
  {
    // Create a default clientToProxyPipeline implementation.
    pipeline.addLast("log", new LoggingHandler(LogLevel.INFO))
    // Enable HTTPS if necessary.
    //          if (ssl) {
    //            val engine: SSLEngine = SecureChatSslContextFactory.getClientContext.createSSLEngine
    //            engine.setUseClientMode(true)
    //            pipeline.addLast("ssl", new SslHandler(engine))
    //          }
    pipeline.addLast("codec", new HttpClientCodec)
    // Remove the following line if you don't want automatic content decompression.
    pipeline.addLast("inflater", new HttpContentDecompressor)
    // Uncomment the following line if you don't want to handle HttpChunks.
    pipeline.addLast("aggregator", new HttpChunkAggregator(1048576));
    pipeline.addLast("proxyToServerHandler", new ProxyToServerHandler(clientToProxyContext,clientToProxyMessage))
  }


  def remoteHandlerInitializer(ctx: ChannelHandlerContext, msg: HttpRequest) = new ProxyToServerHandlerInitializer(ctx, msg)
}


class ProxyHandlerInitializer(implicit chainProxies: MutableList[InetSocketAddress]) extends ChannelInitializer[SocketChannel]
{
  def initChannel(ch: SocketChannel)
  {
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


class ProxyToServerHandler(val clientToProxyContext: ChannelHandlerContext, val clientToProxyMessage: HttpRequest) extends ChannelInboundMessageHandlerAdapter[Any]
{


  val logger = InternalLoggerFactory.getInstance(getClass)


  override def channelActive(ctx: ChannelHandlerContext)
  {
    ctx.write(clientToProxyMessage)
  }


  def messageReceived(ctx: ChannelHandlerContext, msg: Any)
  {
    logger.info("=========================")
    logger.info(msg.toString)
    if (msg.isInstanceOf[DefaultHttpResponse])
    {
      logger.info(msg.asInstanceOf[DefaultHttpResponse].getContent.toString)
    }
    clientToProxyContext.write(msg)
  }
}


class ProxyToServerHandlerInitializer(val clientToProxyContext: ChannelHandlerContext, val clientToProxyMessage: HttpRequest) extends ChannelInitializer[SocketChannel]
{
  def initChannel(ch: SocketChannel)
  {
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
    p.addLast("proxyToServerHandler", new ProxyToServerHandler(clientToProxyContext, clientToProxyMessage))
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