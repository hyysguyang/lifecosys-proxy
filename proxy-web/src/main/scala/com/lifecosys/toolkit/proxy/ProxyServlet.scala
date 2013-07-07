package com.lifecosys.toolkit.proxy

import javax.servlet.http.{ HttpServletResponse, HttpServletRequest, HttpServlet }
import org.jboss.netty.channel._
import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.buffer.{ ChannelBufferInputStream, ChannelBufferOutputStream, ChannelBuffers, ChannelBuffer }
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import java.util.concurrent.Executors
import org.jboss.netty.handler.codec.http._
import org.apache.commons.io.IOUtils
import org.jboss.netty.handler.logging.LoggingHandler
import org.jboss.netty.logging.{ Slf4JLoggerFactory, InternalLoggerFactory }
import javax.servlet.{ ServletOutputStream, AsyncContext }
import org.littleshoot.proxy.{ ProxyUtils, ProxyHttpResponseEncoder }
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.io.{ DefaultHttpRequestParser, HttpTransportMetricsImpl, SessionInputBufferImpl }
import org.apache.http.client.config.RequestConfig
import com.typesafe.scalalogging.slf4j.Logging

/**
 *
 *
 * @author Young Gu
 * @version 1.0 6/21/13 10:28 AM
 */
class ProxyServlet extends HttpServlet with Logging {
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  val error = "ERROR"

  val executor = Executors.newCachedThreadPool()
  val cf = new NioClientSocketChannelFactory(executor, executor)
  val responder = new NettyResponder

  var out: ServletOutputStream = null;

  val httpClient = HttpClients.custom.setDefaultRequestConfig(RequestConfig.custom().setRedirectsEnabled(false).build()).build()

  final class OutboundConnectionHandler() extends SimpleChannelUpstreamHandler {
    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
      val buffer: ChannelBuffer = e.getMessage.asInstanceOf[ChannelBuffer]
      synchronized {
        logger.error(s"Write response: ${buffer.readableBytes} bytes")
        buffer.readBytes(out, buffer.readableBytes)
        out.flush
      }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {

      e.getCause.printStackTrace()
      e.getChannel.close
    }
  }

  val futures = scala.collection.mutable.Map[Host, ChannelFuture]()
  val connectProxyResponse = "HTTP/1.1 200 Connection established\r\n\r\n"
  override def service(request: HttpServletRequest, response: HttpServletResponse) {

    //    val httpclient: CloseableHttpClient = HttpClients.createDefault()
    //val clientContext=HttpClientContext.create()
    //    clientContext.setRequestConfig(RequestConfig.custom().setRedirectsEnabled(false).build())

    logger.error("################Service#####################")
    val proxyRequestChannelBuffer = ChannelBuffers.dynamicBuffer(512)
    val bufferStream = new ChannelBufferOutputStream(proxyRequestChannelBuffer)
    IOUtils.copy(request.getInputStream, bufferStream)
    IOUtils.closeQuietly(bufferStream)
    logger.error(Utils.formatBuffer(proxyRequestChannelBuffer))
    logger.error("################Service#####################")
    try {
      val buffer = new SessionInputBufferImpl(new HttpTransportMetricsImpl(), 1024, 2048, null, null)
      buffer.bind(new ChannelBufferInputStream(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer)))
      val parser = new DefaultHttpRequestParser(buffer)
      val proxyRequest = parser.parse()

      logger.error("#######################" + proxyRequest)

      if (proxyRequest.getRequestLine.getMethod == "CONNECT") {
        response.setStatus(200)
        response.setContentType("application/octet-stream")

        response.setContentLength(connectProxyResponse.getBytes("UTF-8").length)
        response.getOutputStream.write(connectProxyResponse.getBytes("UTF-8"))
        response.getOutputStream.flush()

        return
      }
    } catch {
      case e ⇒
    }

    out = response.getOutputStream
    val host: Host = Host(request.getHeader("proxyHost"))
    val future = futures.get(host) match {
      case Some(f) ⇒ f
      case None ⇒ {
        val cb = new ClientBootstrap(cf)
        cb.setOption("keepAlive", true)
        cb.setOption("connectTimeoutMillis", 1200 * 1000)
        cb.getPipeline.addLast("handler", new OutboundConnectionHandler)
        val channelFuture = cb.connect(host.socketAddress).awaitUninterruptibly()
        futures.put(host, channelFuture)

        channelFuture

      }
    }

    if (!future.isSuccess()) {
      return ;
    }

    var lastWriteFuture: ChannelFuture = null

    val channel = future.getChannel
    response.setStatus(HttpServletResponse.SC_OK)
    response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
    // Initiate chunked encoding by flushing the headers.
    out.flush()

    if (channel.isConnected) {
      channel.write(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer))
    }

    //    var lastWriteFuture: ChannelFuture = null
    //
    //    val channel = future.getChannel
    //    try {
    //      response.setStatus(HttpServletResponse.SC_OK)
    //      response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    //      response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
    //      // Initiate chunked encoding by flushing the headers.
    //      out.flush()
    //
    //
    //
    //
    //      val in: PushbackInputStream = new PushbackInputStream(request.getInputStream)
    //
    //      var continue = true
    //      while (channel.isConnected && continue) {
    //        var buffer: ChannelBuffer = null
    //        try {
    //          buffer = read(in)
    //        }
    //        catch {
    //          case e: EOFException => {
    //            continue = false;
    //          }
    //        }
    //        if (buffer == null) {
    //          continue = false;
    //        }
    //        lastWriteFuture = channel.write(buffer)
    //      }
    //    }
    //    finally {
    //      if (lastWriteFuture == null) {
    //        channel.close
    //      }
    //      else {
    //        lastWriteFuture.addListener(ChannelFutureListener.CLOSE)
    //      }
    //    }

    //
    //    //    val httpclient: CloseableHttpClient = HttpClients.createDefault()
    //    //val clientContext=HttpClientContext.create()
    //    //    clientContext.setRequestConfig(RequestConfig.custom().setRedirectsEnabled(false).build())
    //    val buffer = new SessionInputBufferImpl(new HttpTransportMetricsImpl(), 1024, 2048, null, null);
    //    buffer.bind(request.getInputStream)
    //    val parser = new DefaultHttpRequestParser(buffer);
    //    val proxyRequest = parser.parse()
    //
    //    println("#######################" + proxyRequest)
    //
    //    if (proxyRequest.getRequestLine.getMethod == "CONNECT") {
    //      response.setStatus(200)
    //      return
    //    }
    //
    //    val httpHost = new HttpHost(Host(request.getHeader("proxyHost")).host, Host(request.getHeader("proxyHost")).port)
    //    val proxyResponse = synchronized(httpClient.execute(httpHost, proxyRequest))
    //    try {
    //      response.setStatus(proxyResponse.getStatusLine.getStatusCode)
    //      val entity: HttpEntity = proxyResponse.getEntity
    //      if (entity.getContentType != null) response.setContentType(entity.getContentType.getValue)
    //      if (entity.getContentLength >= 0) response.setContentLength(entity.getContentLength.toInt)
    //      if (entity.getContentEncoding != null) response.setCharacterEncoding(entity.getContentEncoding.getValue)
    //      proxyResponse.getAllHeaders.toList.foreach {
    //        header => response.addHeader(header.getName, header.getValue)
    //      }
    //      val content = entity.getContent
    //      IOUtils.copy(content, response.getOutputStream)
    //      response.getOutputStream.flush()
    //      IOUtils.closeQuietly(entity.getContent)
    //    }
    //    finally {
    //      proxyResponse.close
    //    }

    //
    //
    //   implicit val asyncContext = request.startAsync()
    //    asyncContext.setTimeout(1200000)
    //    asyncContext.addListener {
    //      new AsyncListener {
    //        def onTimeout(event: AsyncEvent): Unit = {
    //          asyncContext.getResponse.getOutputStream.write(error.getBytes)
    //          asyncContext.getResponse.getOutputStream.flush()
    //          asyncContext.complete()
    //        }
    //        def onError(event: AsyncEvent): Unit = {
    //          logger.error("Error.......")
    //        }
    //        def onStartAsync(event: AsyncEvent): Unit = {}
    //        def onComplete(event: AsyncEvent): Unit = {}
    //      }
    //    }
    //
    //    val proxyRequestChannelBuffer=ChannelBuffers.dynamicBuffer(512)
    //    val bufferStream = new ChannelBufferOutputStream(proxyRequestChannelBuffer)
    //    IOUtils.copy(request.getInputStream,bufferStream)
    //    IOUtils.closeQuietly(bufferStream)
    //
    //    asyncContext.start(new Runnable {
    //      def run() {
    ////        asyncContext.getResponse.setContentType("application/octet-stream")
    ////        asyncContext.getResponse.asInstanceOf[HttpServletResponse].setHeader("Transfer-Encoding","identity")
    ////        asyncContext.getResponse.setContentLength("yhid hfdhdshdshfd hdfsh".getBytes().length)
    ////        asyncContext.getResponse.getOutputStream.write("yhid hfdhdshdshfd hdfsh".getBytes())
    ////        asyncContext.getResponse.getOutputStream.flush()
    ////        asyncContext.complete()
    //        responder.fetchRemoteResource(proxyRequestChannelBuffer)
    //      }
    //    })

  }

  class NettyResponder() {
    def fetchRemoteResource(proxyRequestChannelBuffer: ChannelBuffer)(implicit asyncContext: AsyncContext) = {

      val request = asyncContext.getRequest.asInstanceOf[HttpServletRequest]
      val response = asyncContext.getResponse

      println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$")
      println(IOUtils.toString(request.getInputStream))
      response.setContentType("application/octet-stream")
      response.asInstanceOf[HttpServletResponse].setHeader("Transfer-Encoding", "deflate")
      val format = classOf[LoggingHandler].getDeclaredMethods.find(_.getName == "formatBuffer").get
      format.setAccessible(true)
      println("Receipt proxy request" + Utils.formatBuffer(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer)))

      val cb = new ClientBootstrap(cf)
      cb.setOption("keepAlive", true)
      cb.setOption("connectTimeoutMillis", 120 * 1000)
      //      cb.getPipeline.addFirst("logger", new LoggingHandler(InternalLogLevel.WARN))
      cb.getPipeline.addLast("decoder", new HttpResponseDecoder)
      cb.getPipeline.addLast("handler", new OutboundHandler())
      val future = cb.connect(Host(request.getHeader("proxyHost")).socketAddress)
      future.addListener(new ChannelFutureListener {
        def operationComplete(future: ChannelFuture) {
          if (future.isSuccess) {
            future.getChannel().write(proxyRequestChannelBuffer).addListener {
              future: ChannelFuture ⇒ logger.debug("Write request to remote server %s completed.".format(future.getChannel))
            }
          } else {
            logger.debug("Close browser connection...")
            response.getOutputStream.write("Can't connect to target server.".getBytes())
          }
        }
      })
    }
  }

  class OutboundHandler(implicit asyncContext: AsyncContext) extends SimpleChannelUpstreamHandler {
    val stream = asyncContext.getResponse.getOutputStream

    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
      try {
        println("messageReceived:                  " + e.getMessage)
        if (e.getMessage.isInstanceOf[HttpResponse] || e.getMessage.isInstanceOf[HttpChunk]) {
          val proxyResponse = e.getMessage

          if (proxyResponse.isInstanceOf[HttpResponse]) {
            ProxyUtils.stripHopByHopHeaders(proxyResponse.asInstanceOf[HttpResponse])
            ProxyUtils.addVia(proxyResponse.asInstanceOf[HttpResponse])
          }

          val encode = classOf[HttpResponseEncoder].getSuperclass.getDeclaredMethods.filter(_.getName == "encode")(0)
          encode.setAccessible(true)
          val responseBuffer = encode.invoke(new ProxyHttpResponseEncoder(), null, ctx.getChannel, proxyResponse).asInstanceOf[ChannelBuffer]
          asyncContext.getResponse.setContentLength(responseBuffer.readableBytes())

          val copiedBuffer = ChannelBuffers.copiedBuffer(responseBuffer)
          println(s"Write response: length=  ${copiedBuffer.readableBytes()} Content: \n" + Utils.formatBuffer(copiedBuffer))

          stream.write(ChannelBuffers.copiedBuffer(ChannelBuffers.copiedBuffer(responseBuffer)).array())
          stream.flush()
          asyncContext.complete()
        }
      } catch {
        case e: Throwable ⇒ e.printStackTrace()
      }
    }

    override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      logger.warn("channelClosed" + e.getChannel)
      stream.write(error.getBytes)
      stream.flush()
      asyncContext.complete()
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      e.getCause.printStackTrace
      Utils.closeChannel(e.getChannel)
    }

  }

}
