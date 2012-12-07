package com.lifecosys.toolkit.proxy

import java.net.InetSocketAddress
import org.jboss.netty.logging.{InternalLogLevel, Slf4JLoggerFactory, InternalLoggerFactory}
import com.twitter.finagle.builder.Server
import java.util.regex.Pattern
import org.apache.commons.lang.StringUtils
import com.twitter.conversions.time._
import com.twitter.conversions.storage._
import collection.mutable
import com.twitter.finagle.Service
import org.jboss.netty.handler.codec.http.HttpRequest
import org.jboss.netty.handler.codec.http.HttpResponse
import com.twitter.finagle.builder.ServerBuilder
import com.twitter.util.StorageUnit
import com.twitter.finagle.http.codec.ChannelBufferUsageTracker
import com.twitter.finagle.http.Http
import com.twitter.finagle.ServerCodecConfig
import com.twitter.finagle.Codec
import org.jboss.netty.channel.ChannelPipelineFactory
import org.jboss.netty.handler.logging.LoggingHandler
import com.twitter.finagle.ServiceFactory
import com.twitter.finagle.SimpleFilter
import org.jboss.netty.handler.codec.http.HttpResponseStatus._
import org.jboss.netty.handler.codec.http.DefaultHttpResponse
import org.jboss.netty.handler.codec.http.HttpVersion._
import org.jboss.netty.buffer.ChannelBuffers._
import org.jboss.netty.util.CharsetUtil._
import scala.Some
import com.twitter.finagle.builder.ClientBuilder
import org.jboss.netty.util.CharsetUtil
import com.twitter.util.Future

/**
 * Created with IntelliJ IDEA.
 * User: young
 * Date: 12/2/12
 * Time: 1:44 AM
 * To change this template use File | Settings | File Templates.
 */

object Proxy {
  def apply(port: Int) = new Proxy(port)


}

class Proxy(port: Int, logLevel: InternalLogLevel = InternalLogLevel.WARN) {


  val chainProxies = mutable.MutableList[InetSocketAddress]()

  //    chainProxies.split(":") match
  //            {
  //              case Array(host) => new InetSocketAddress(host, 80)
  //              case Array(host, port) if port.forall(_.isDigit) => new InetSocketAddress(host, port.toInt)
  //              case _ => sys.error("Invalid host")
  //            }


  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  val logger = InternalLoggerFactory.getInstance(getClass)

  var server: Option[Server] = scala.None

  Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
    def run {
      logger.info("Shutdown proxy server now.............")
      server.get.close(60.second)
    }
  }))

  def start = {
    def server = {
      val handleExceptions = new HandleExceptions
      val respond = new Respond
      val myService: Service[HttpRequest, HttpResponse] = handleExceptions andThen respond
      ServerBuilder().codec(ProxyHttpCodecFactory())
              //            .logChannelActivity(true)
              .bindTo(new InetSocketAddress(port))
              .name("ProxyServer")
              .build(myService)
    }
    this.server = Some(server)
  }

  def shutdown = {
    server match {
      case Some(server) => server.close(60.second)
      case None =>
    }
  }


  case class ProxyHttpCodecFactory(override val _compressionLevel: Int = 0,
                                   override val _maxRequestSize: StorageUnit = 1.megabyte,
                                   override val _maxResponseSize: StorageUnit = 1.megabyte,
                                   override val _decompressionEnabled: Boolean = true,
                                   override val _channelBufferUsageTracker: Option[ChannelBufferUsageTracker] = None,
                                   override val _annotateCipherHeader: Option[String] = None,
                                   override val _enableTracing: Boolean = false,
                                   override val _maxInitialLineLength: StorageUnit = 4096.bytes,
                                   override val _maxHeaderSize: StorageUnit = 8192.bytes)
          extends Http(_compressionLevel, _maxRequestSize, _maxResponseSize, _decompressionEnabled, _channelBufferUsageTracker, _annotateCipherHeader, _enableTracing, _maxInitialLineLength, _maxHeaderSize) {

    override def client = super.client

    override def server: (ServerCodecConfig) => Codec[HttpRequest, HttpResponse] = {
      config =>
        val codec = super.server(config)
        new Codec[HttpRequest, HttpResponse] {
          override def pipelineFactory: ChannelPipelineFactory =
            new ChannelPipelineFactory {
              override def getPipeline() = {
                val pipeline = codec.pipelineFactory.getPipeline
                if (logLevel == InternalLogLevel.DEBUG) {
                  pipeline.addFirst("logger", new LoggingHandler(InternalLogLevel.DEBUG))
                }
                pipeline
              }
            }

          override def prepareConnFactory(underlying: ServiceFactory[HttpRequest, HttpResponse]): ServiceFactory[HttpRequest, HttpResponse] = {
            codec.prepareConnFactory(underlying)
          }
        }
    }
  }


  /**
   * A simple Filter that catches exceptions and converts them to appropriate
   * HTTP responses.
   */
  class HandleExceptions extends SimpleFilter[HttpRequest, HttpResponse] {
    def apply(request: HttpRequest, service: Service[HttpRequest, HttpResponse]) = {

      // `handle` asynchronously handles exceptions.
      service(request) handle {
        case error =>
          val statusCode = error match {
            case _: IllegalArgumentException =>
              FORBIDDEN
            case _ =>
              INTERNAL_SERVER_ERROR
          }
          val errorResponse = new DefaultHttpResponse(HTTP_1_1, statusCode)
          errorResponse.setContent(copiedBuffer(error.getLocalizedMessage, UTF_8))

          errorResponse
      }

    }


  }


  class Respond extends Service[HttpRequest, HttpResponse] {
    private var HTTP_PREFIX: Pattern = Pattern.compile("http.*", Pattern.CASE_INSENSITIVE)
    private var HTTPS_PREFIX: Pattern = Pattern.compile("https.*", Pattern.CASE_INSENSITIVE)

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


    def apply(request: HttpRequest) = {


      //      var host = request match
      //      {
      //        case req if req.getMethod == HttpMethod.CONNECT =>{request.getUri.split(":") match
      //        {
      //          case Array(host) => new InetSocketAddress(host, 80)
      //          case Array(host, port) if port.forall(_.isDigit) => new InetSocketAddress(host, port.toInt)
      //          case _ => sys.error("Invalid host")
      //        }}
      //      }


      //      val uri: URI = new URI("http://www.apple.com/")
      //      if (port == -1)
      //      {
      //        if ("http".equalsIgnoreCase(scheme))
      //        {
      //          port = 80
      //        } else if ("https".equalsIgnoreCase(scheme))
      //        {
      //          port = 443
      //        }
      //      }
      //      request.getMethod match {
      //        case HttpMethod.CONNECT =>{
      //          val hostAndPort: (String, Int) = parseHostAndPort(request.getUri)
      //          val client = ClientBuilder()
      //.codec(Http())
      //.hosts(new InetSocketAddress(hostAndPort._1,hostAndPort._2))
      //.hostConnectionLimit(5)
      //.tcpConnectTimeout(5.second)
      //.requestTimeout(60.second)
      //.name("HttpClient")
      //.hostConnectionIdleTime(550.seconds * 3)
      //.hostConnectionMaxIdleTime(550.seconds * 3)
      //.hostConnectionMaxLifeTime(300.minutes).build()
      //
      //          val response = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK)
      //          response.addHeader("Connection","Keep-Alive")
      //          response.addHeader("Proxy-Connection","Keep-Alive")
      //          Future.value(response)
      //        }
      //        case _ => Future.value(new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK))
      //      }

      val host = chainProxies.get(0).getOrElse(parseHostAndPort(request.getUri))
      val client = ClientBuilder()
              .codec(Http())
              .hosts(host)
              .hostConnectionLimit(5)
              .tcpConnectTimeout(5.second)
              .requestTimeout(60.second)
              .name("HttpClient")
              .hostConnectionIdleTime(550.seconds * 3)
              .hostConnectionMaxIdleTime(550.seconds * 3)
              .hostConnectionMaxLifeTime(300.minutes).build()


      println(request.getMethod + "\t" + request.getUri)


      //      val requestStub: HttpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri.getRawPath)
      //      requestStub.setHeader(HttpHeaders.Names.HOST, uri.getHost)
      //      requestStub.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE)
      //      requestStub.setHeader(HttpHeaders.Names.ACCEPT_ENCODING, HttpHeaders.Values.GZIP)

      client(request) onSuccess {
        response =>
          val responseString = response.getContent.toString(CharsetUtil.UTF_8)
          Future.value(response)
      } onFailure {
        error =>
          println("))) Unauthorized request errored (as desired): " + error.getClass.getName)
      }


      //
      //      // compose the Filter with the client:
      //      val client: Service[HttpRequest, HttpResponse] = handleErrors andThen clientWithoutErrorHandling
      //
      //      println("))) Issuing two requests in parallel: ")
      //      val request1 = makeAuthorizedRequest(client)
      //      //    val request2 = makeUnauthorizedRequest(client)
      //
      //      request1.ensure( client.release())
      //
      //

      //      val response = new DefaultHttpResponse(HTTP_1_1, OK)
      //      response.setContent(copiedBuffer("hello world", UTF_8))
      //      Future.value(response)
    }

  }

}

/*
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
    //val engine: SSLEngine = SecureChatSslContextFactory.getClientContext.createSSLEngine
    //engine.setUseClientMode(true)
    //pipeline.addLast("ssl", new SslHandler(engine))
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
    //val engine: SSLEngine = SecureChatSslContextFactory.getClientContext.createSSLEngine
    //engine.setUseClientMode(true)
    //p.addLast("ssl", new SslHandler(engine))
    //          }
    p.addLast("codec", new HttpClientCodec)
    // Remove the following line if you don't want automatic content decompression.
    p.addLast("inflater", new HttpContentDecompressor)
    // Uncomment the following line if you don't want to handle HttpChunks.
    p.addLast("aggregator", new HttpChunkAggregator(1048576));
    p.addLast("proxyToServerHandler", new ProxyToServerHandler(clientToProxyContext, clientToProxyMessage))
    //          p.addLast("outboundHandler", new ChannelOutboundMessageHandlerAdapter[Object]{
    //override def newOutboundBuffer(clientToProxyContext: ChannelHandlerContext): MessageBuf[Object] = {
    //
    //  super.newOutboundBuffer(clientToProxyContext)
    //
    //}
    //
    //
    //override def flush(clientToProxyContext: ChannelHandlerContext, future: ChannelFuture)
    //{
    //  println("-------------------------------")
    //  clientToProxyContext.flush(future)
    //}
    //
    //override def freeOutboundBuffer(clientToProxyContext: ChannelHandlerContext, buf: ChannelBuf)
    //{
    //  super.freeOutboundBuffer(clientToProxyContext, buf)
    //}
    //          })
  }
}*/
