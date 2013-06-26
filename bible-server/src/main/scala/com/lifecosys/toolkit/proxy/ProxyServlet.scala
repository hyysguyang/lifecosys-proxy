package com.lifecosys.toolkit.proxy

import javax.servlet.http.{HttpServletResponse, HttpServletRequest, HttpServlet}
import org.jboss.netty.channel._
import org.jboss.netty.bootstrap.ClientBootstrap
import java.net.InetSocketAddress
import org.jboss.netty.buffer.{ChannelBufferInputStream, ChannelBufferOutputStream, ChannelBuffers, ChannelBuffer}
import org.jboss.netty.channel.socket.ClientSocketChannelFactory
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import java.util.concurrent.Executors
import java.io.{InputStream, OutputStream}
import org.jboss.netty.handler.codec.http._
import org.apache.commons.io.IOUtils
import org.jboss.netty.handler.logging.LoggingHandler
import org.jboss.netty.logging.{InternalLogLevel, Slf4JLoggerFactory, InternalLoggerFactory}
import javax.servlet.{ServletOutputStream, AsyncContext, AsyncEvent, AsyncListener}
import org.littleshoot.proxy.{ProxyUtils, ProxyHttpResponseEncoder}
import org.apache.http.client.methods.{CloseableHttpResponse, HttpGet}
import org.apache.http.{HttpHost, HttpEntity}
import org.apache.http.util.EntityUtils
import org.apache.http.impl.client.{HttpClientBuilder, HttpClients, CloseableHttpClient}
import org.apache.http.impl.io.{DefaultHttpRequestParser, HttpTransportMetricsImpl, SessionInputBufferImpl}
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.config.RequestConfig.Builder
import org.apache.http.client.protocol.HttpClientContext

/**
 *
 *
 * @author Young Gu 
 * @version 1.0 6/21/13 10:28 AM
 */
class ProxyServlet extends HttpServlet {
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  val error = "ERROR"

  val executor = Executors.newCachedThreadPool()
  val cf = new NioClientSocketChannelFactory(executor, executor)
  val responder = new NettyResponder

  val httpClient = HttpClients.custom.setDefaultRequestConfig(RequestConfig.custom().setRedirectsEnabled(false).build()).build()

  override def service(request: HttpServletRequest, response: HttpServletResponse) {
    //    val httpclient: CloseableHttpClient = HttpClients.createDefault()
    //val clientContext=HttpClientContext.create()
    //    clientContext.setRequestConfig(RequestConfig.custom().setRedirectsEnabled(false).build())
    val buffer = new SessionInputBufferImpl(new HttpTransportMetricsImpl(), 1024, 2048, null, null);
    buffer.bind(request.getInputStream)
    val parser = new DefaultHttpRequestParser(buffer);
    val proxyRequest = parser.parse()

    println("#######################" + proxyRequest)

    val httpHost = new HttpHost(Host(request.getHeader("proxyHost")).host, Host(request.getHeader("proxyHost")).port)
    val proxyResponse = synchronized(httpClient.execute(httpHost, proxyRequest))
    try {
      response.setStatus(proxyResponse.getStatusLine.getStatusCode)
      val entity: HttpEntity = proxyResponse.getEntity
      if (entity.getContentType != null) response.setContentType(entity.getContentType.getValue)
      if (entity.getContentLength >= 0) response.setContentLength(entity.getContentLength.toInt)
      if (entity.getContentEncoding != null) response.setCharacterEncoding(entity.getContentEncoding.getValue)
      proxyResponse.getAllHeaders.toList.foreach {
        header => response.addHeader(header.getName, header.getValue)
      }
      val content = entity.getContent
      IOUtils.copy(content, response.getOutputStream)
      response.getOutputStream.flush()
      IOUtils.closeQuietly(entity.getContent)
    }
    finally {
      proxyResponse.close
    }

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
        case e: Throwable => e.printStackTrace()
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
