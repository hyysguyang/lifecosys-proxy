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
import org.apache.commons.lang3.StringUtils

/**
 *
 *
 * @author Young Gu
 * @version 1.0 6/21/13 10:28 AM
 */
class OutboundConnectionHandler(var response:HttpServletResponse) extends SimpleChannelUpstreamHandler with Logging {
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    val buffer: ChannelBuffer = e.getMessage.asInstanceOf[ChannelBuffer]
    synchronized {
      logger.debug(s"###########Receive (${buffer.readableBytes})bytes from remote server###########")
      logger.debug(Utils.formatBuffer(ChannelBuffers.copiedBuffer(buffer)))
      logger.debug("##################################################")
      buffer.readBytes(response.getOutputStream, buffer.readableBytes)
      response.getOutputStream.flush
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {

    e.getCause.printStackTrace()
    e.getChannel.close
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.error("###################3Got closed event on : %s".format(e.getChannel))
  }
}


case class ChannelKey(sessionId:String,host:Host)

class ChannelManager{
  private[this] val channels=scala.collection.mutable.Map[ChannelKey,Channel]()
  def get(channelKey:ChannelKey)=channels.get(channelKey)
  def add(channelKey:ChannelKey,channel:Channel)=channels += channelKey -> channel
}

object DefaultChannelManager extends ChannelManager

class ProxyServlet extends HttpServlet with Logging {
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  val error = "ERROR"

  val executor = Executors.newCachedThreadPool()
  val cf = new NioClientSocketChannelFactory(executor, executor)
  val responder = new NettyResponder

  val CONNECT_LENGTH=HttpMethod.CONNECT.getName.getBytes(Utils.UTF8).length


  val httpClient = HttpClients.custom.setDefaultRequestConfig(RequestConfig.custom().setRedirectsEnabled(false).build()).build()


  override def service(request: HttpServletRequest, response: HttpServletResponse) {

    //    val httpclient: CloseableHttpClient = HttpClients.createDefault()
    //val clientContext=HttpClientContext.create()
    //    clientContext.setRequestConfig(RequestConfig.custom().setRedirectsEnabled(false).build())


    logger.error(s"########Request Session ID: ${request.getRequestedSessionId}, ${request.getSession(false)}")
    logger.error(s"########Request ${request}, ${response}")

    if(StringUtils.isEmpty(request.getRequestedSessionId) && request.getSession(false)==null){
      request.getSession(true)
    }

    require(StringUtils.isNotEmpty(request.getSession.getId),"Session have not been created, server error.")
    val proxyRequestChannelBuffer = ChannelBuffers.dynamicBuffer(512)
    val bufferStream = new ChannelBufferOutputStream(proxyRequestChannelBuffer)
    IOUtils.copy(request.getInputStream, bufferStream)
    IOUtils.closeQuietly(bufferStream)

    val proxyHost = Host(request.getHeader("proxyHost"))
    val channelKey: ChannelKey = ChannelKey(request.getSession.getId, proxyHost)

    if(proxyRequestChannelBuffer.readableBytes()==CONNECT_LENGTH &&
    HttpMethod.CONNECT.getName == IOUtils.toString(new ChannelBufferInputStream(proxyRequestChannelBuffer))){
      val cb = new ClientBootstrap(cf)
      cb.setOption("keepAlive", true)
      cb.setOption("connectTimeoutMillis", 1200 * 1000)
      cb.getPipeline.addLast("handler", new OutboundConnectionHandler(response))
      val channelFuture = cb.connect(proxyHost.socketAddress).awaitUninterruptibly()
      if (channelFuture.isSuccess() && channelFuture.getChannel.isConnected) {
        DefaultChannelManager.add(channelKey,channelFuture.getChannel)
        response.setStatus(200)
        response.setContentType("application/octet-stream")
        response.setContentLength(Utils.connectProxyResponse.getBytes("UTF-8").length)
        response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
        response.getOutputStream.flush()
        return
      }
      else{//todo: error process
        channelFuture.getChannel.close()
        response.setStatus(200)
        response.setContentType("application/octet-stream")
        response.setContentLength("HTTP/1.1 400 Can't establish connection\r\n\r\n".getBytes("UTF-8").length)
        response.getOutputStream.write("HTTP/1.1 400 Can't establish connection\r\n\r\n".getBytes("UTF-8"))
        response.getOutputStream.flush()
        return
      }

    }
//    try {
//      val buffer = new SessionInputBufferImpl(new HttpTransportMetricsImpl(), 1024, 2048, null, null)
//      buffer.bind(new ChannelBufferInputStream(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer)))
//      val parser = new DefaultHttpRequestParser(buffer)
//      val proxyRequest = parser.parse()
//
//      logger.error("#######################" + proxyRequest)
//
//      if (proxyRequest.getRequestLine.getMethod == "CONNECT") {
//        response.setStatus(200)
//        response.setContentType("application/octet-stream")
//        response.setContentLength(connectProxyResponse.getBytes("UTF-8").length)
//        response.getOutputStream.write(connectProxyResponse.getBytes("UTF-8"))
//        response.getOutputStream.flush()
//
//        return
//      }
//    } catch {
//      case e ⇒
//    }



//    val future = futures.get(host) match {
//      case Some(f) ⇒ {
//        logger.error(f.getChannel.getPipeline.get(classOf[OutboundConnectionHandler]).response.hashCode()+"2222222222222")
//        logger.error(f.getChannel.getPipeline.get(classOf[OutboundConnectionHandler]).response.hashCode()+"2222222222222")
//        f
//      }
//      case None ⇒ {
//        val cb = new ClientBootstrap(cf)
//        cb.setOption("keepAlive", true)
//        cb.setOption("connectTimeoutMillis", 1200 * 1000)
//        cb.getPipeline.addLast("handler", new OutboundConnectionHandler(response))
//        val channelFuture = cb.connect(host.socketAddress).awaitUninterruptibly()
//        futures.put(host, channelFuture)
//
//        channelFuture

//      }
//    }
val channel=DefaultChannelManager.get(channelKey).get
    channel.getPipeline.get(classOf[OutboundConnectionHandler]).response=response
    if (channel.isConnected) {
      logger.debug(s"###########Write proxy request buffer (${proxyRequestChannelBuffer.readableBytes})bytes to remote server###########")
      logger.debug(Utils.formatBuffer(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer)))
      logger.debug("##################################################")

      response.setStatus(HttpServletResponse.SC_OK)
      response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
      response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
      // Initiate chunked encoding by flushing the headers.
      response.getOutputStream.flush()

      channel.write(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer))
    }else{
      //todo: error process
      channel.close()
      response.setStatus(200)
      response.setContentType("application/octet-stream")
      response.setContentLength("HTTP/1.1 400 Can't establish connection\r\n\r\n".getBytes("UTF-8").length)
      response.getOutputStream.write("HTTP/1.1 400 Can't establish connection\r\n\r\n".getBytes("UTF-8"))
      response.getOutputStream.flush()
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
