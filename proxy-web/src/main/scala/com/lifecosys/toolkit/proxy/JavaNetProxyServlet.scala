package com.lifecosys.toolkit.proxy

import com.lifecosys.toolkit.proxy.ChannelManager
import javax.servlet.http.{ HttpServletResponse, HttpServletRequest, HttpServlet }
import org.jboss.netty.channel._
import org.jboss.netty.buffer._
import org.jboss.netty.channel.socket.nio.{ NioWorker, NioWorkerPool, NioClientSocketChannelFactory }
import java.util.concurrent.{ Executor, Executors }
import org.jboss.netty.handler.codec.http._
import org.apache.commons.io.IOUtils
import org.jboss.netty.logging.{ Slf4JLoggerFactory, InternalLoggerFactory }
import com.typesafe.scalalogging.slf4j.Logging
import org.apache.commons.lang3.StringUtils
import java.net.{ InetSocketAddress, Socket }
import java.nio.channels.ClosedChannelException
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import scala.collection.mutable

/**
 *
 *
 * @author Young Gu
 * @version 1.0 6/21/13 10:28 AM
 * @deprecated
 */
class JavaNetProxyServlet extends HttpServlet with Logging {
  val executor = Executors.newCachedThreadPool()

  val pool: NioWorkerPool = new NioWorkerPool(executor, 100) {
    override def newWorker(executor: Executor): NioWorker = {
      new NioWorker(executor, null)
    }
  }
  val clientSocketChannelFactory = new NioClientSocketChannelFactory(executor, 1, pool)

  override def service(request: HttpServletRequest, response: HttpServletResponse) {
    if (StringUtils.isEmpty(request.getRequestedSessionId) && request.getSession(false) == null) {
      request.getSession(true)
      logger.debug(s"Created session: ${request.getSession.getId} for request: ${request}")
    }

    require(StringUtils.isNotEmpty(request.getSession.getId), "Session have not been created, server error.")

    val proxyRequestChannelBuffer = ChannelBuffers.dynamicBuffer(512)
    val bufferStream = new ChannelBufferOutputStream(proxyRequestChannelBuffer)
    IOUtils.copy(request.getInputStream, bufferStream)
    IOUtils.closeQuietly(bufferStream)

    logger.debug(s"############Process payload ###############\n${Utils.formatMessage(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer))}")

    val proxyHost = Host(request.getHeader(ProxyHostHeader.name))
    val channelKey: ChannelKey = ChannelKey(request.getSession.getId, proxyHost)

    if (HttpMethod.CONNECT.getName != request.getHeader("proxyRequestMethod") && "HTTPS-DATA-TRANSFER" != request.getHeader("proxyRequestMethod")) {
      new HttpRequestProcessor().process(response, proxyHost, channelKey, proxyRequestChannelBuffer)
    } else {

      if (HttpMethod.CONNECT.getName == request.getHeader("proxyRequestMethod")) {
        val socket = new Socket()
        socket.setKeepAlive(true)
        socket.setTcpNoDelay(true)
        socket.setSoTimeout(1000 * 1000)
        socket.connect(proxyHost.socketAddress, 30 * 1000)
        request.getSession(false).setAttribute("socket", socket)
        response.setContentType("application/octet-stream")
        response.setHeader(ResponseCompleted.name, "true")
        response.setContentLength(Utils.connectProxyResponse.getBytes("UTF-8").length)
        response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
        response.getOutputStream.flush()
      } else {
        response.setStatus(HttpServletResponse.SC_OK)
        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
        response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
        // Initiate chunked encoding by flushing the headers.
        response.getOutputStream.flush()

        val socket = request.getSession(false).getAttribute("socket").asInstanceOf[Socket]
        val socketOutput = socket.getOutputStream
        socketOutput.write(proxyRequestChannelBuffer.array())
        socketOutput.flush()
        val socketInput = socket.getInputStream
        val buffer = new Array[Byte](512)
        var size = socketInput.read(buffer)

        while (size != -1) {
          logger.error(s"Write response data ${size} to browser ${Utils.hexDumpToString(buffer)}")
          response.getOutputStream.write(buffer, 0, size)
          size = socketInput.read(buffer)
        }

        //
        //        for(readBytes <- Some(socketInput.read(buffer)); if readBytes != -1){
        //          logger.error(s"Write response data ${buffer.size} to browser ${Utils.hexDumpToString(buffer)}")
        //          response.getOutputStream.write(buffer,0,readBytes)
        //        }
        response.flushBuffer()

        //        val flag: Long = System.currentTimeMillis()
        //        request.getSession(false).setAttribute("flag", flag)
        //        while (!(request.getSession(false).getAttribute("flag").asInstanceOf[Long] > flag)) {
        //          logger.error(s"Reading data from $socket")
        //          val message = new ByteArrayOutputStream()
        //          val socketInput=socket.getInputStream
        ////          IOUtils.copyLarge(socketInput,response.getOutputStream,0,1)
        ////          IOUtils.copyLarge(socketInput,response.getOutputStream,0,socketInput.available())
        ////          response.flushBuffer()
        //
        //          val buffer=new Array[Byte](512)
        //          for(readBytes <- Some(socketInput.read(buffer)); if readBytes != -1){
        //            response.getOutputStream.write(buffer,0,readBytes)
        //          }
        //                    response.flushBuffer()
        ////
        ////
        ////          IOUtils.copyLarge(socketInput, message, 0, 1)
        ////          while (socketInput.available() > 0) {
        ////            IOUtils.copyLarge(socketInput, message, 0, socketInput.available())
        ////          }
        ////          val array: Array[Byte] = message.toByteArray
        ////          logger.error(s"Write response data to browser ${Utils.hexDumpToString(array)}")
        //          //          response.getOutputStream.write(array)
        ////          response.flushBuffer()
        //
        //        }

        //      if(!httpsProcessors.get(channelKey).isDefined)
        //        httpsProcessors.put(channelKey,new HttpsRequestProcessor)
        //
        //      httpsProcessors.get(channelKey).get.processHttps(request, response, proxyHost, channelKey, proxyRequestChannelBuffer)
      }
    }
  }

  val httpsProcessor = new HttpsRequestProcessor()
  System.setProperty("javax.net.debug", "all")

  private[this] val sockets = scala.collection.mutable.Map[ChannelKey, Socket]()
  private[this] val httpsProcessors = scala.collection.mutable.Map[ChannelKey, HttpsRequestProcessor]()

  class HttpsRequestProcessor {
    var requestQueue = new mutable.Queue[ChannelBuffer]()

    def processHttps(request: HttpServletRequest, response: HttpServletResponse, proxyHost: Host, channelKey: ChannelKey, proxyRequestChannelBuffer: ChannelBuffer) {

      requestQueue.enqueue(proxyRequestChannelBuffer)
      if (HttpMethod.CONNECT.getName == request.getHeader("proxyRequestMethod")) {

        val socket = new Socket()
        socket.setKeepAlive(true)
        socket.setTcpNoDelay(true)
        socket.setSoTimeout(1000 * 120)
        socket.connect(proxyHost.socketAddress, 30 * 1000)
        if (socket.isConnected) {
          response.setStatus(200)
          response.setContentType("application/octet-stream")
          response.setHeader(ResponseCompleted.name, "true")
          response.setContentLength(Utils.connectProxyResponse.getBytes("UTF-8").length)
          response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
          response.getOutputStream.flush()
          synchronized(requestQueue.dequeue())
          return

        } else {
          //todo: error process
          logger.error("Can't establish connection")
          socket.close()
          writeErrorResponse(response)
          return
        }

      } else if ("HTTPS-DATA-TRANSFER" == request.getHeader("proxyRequestMethod")) {

        try {
          //          val socket = channelManager.get(channelKey).get
          //          val socketOutput = socket.getOutputStream
          //          val socketInput = socket.getInputStream
          //          socketOutput.flush()
          //          synchronized {
          //            val requestMessage: ChannelBuffer = requestQueue.dequeue()
          //            logger.error(s"Write request to ${proxyHost}\n ${Utils.formatMessage(requestMessage)}")
          //            logger.error(s"#########${socket.isConnected}")
          //            socketOutput.write(requestMessage.array())
          //            socketOutput.flush()
          //            val message = new ByteArrayOutputStream()
          //            IOUtils.copyLarge(socketInput, message, 0, 1024)
          //            while (socketInput.available() > 0) {
          //              IOUtils.copyLarge(socketInput, message, 0, socketInput.available())
          //            }
          //            //
          //            //          for (length <- Some(socketInput.read(buffer)); if length > 0) {
          //            //            message.write(buffer, 0, length)
          //            //            total += length
          //            //          }
          //            val responseData = message.toByteArray
          //            logger.error(s"Receive message ${Utils.hexDumpToString(responseData)}")
          //            response.setContentLength(responseData.length)
          //            response.setStatus(HttpServletResponse.SC_OK)
          //            response.setHeader(ResponseCompleted.name, "true")
          //            response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
          //            response.getOutputStream.write(responseData)
          //            response.getOutputStream.flush()
          //
          //
          //          }

        } catch {
          case e ⇒ e.printStackTrace()
        }
      }

    }
  }

  class HttpRequestProcessor {
    def process(response: HttpServletResponse, proxyHost: Host, channelKey: ChannelKey, proxyRequestChannelBuffer: ChannelBuffer) {
      val socket = new Socket()
      socket.setKeepAlive(true)
      socket.setTcpNoDelay(true)
      socket.setSoTimeout(1000 * 1200)
      socket.connect(proxyHost.socketAddress)

      val output = socket.getOutputStream
      val input = socket.getInputStream
      output.flush()
      output.write(proxyRequestChannelBuffer.array())
      output.flush()

      var total = 0
      val buffer = new Array[Byte](1024)
      for (length ← Some(input.read(buffer)); if length > 0) {
        response.getOutputStream.write(buffer, 0, length)
        total += length
      }
      response.setContentLength(total)
      response.setStatus(HttpServletResponse.SC_OK)
      response.setHeader(ResponseCompleted.name, "true")
      response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
      response.getOutputStream.flush()

      //      if (channelFuture.isSuccess() && channelFuture.getChannel.isConnected) {
      //        channelManager.add(channelKey, channelFuture.getChannel)
      //      } else {
      //        channelFuture.getChannel.close()
      //        writeErrorResponse(response)
      //        return
      //      }
      //      val channel = channelManager.get(channelKey).get
      //      channel.getPipeline.get(classOf[HttpOutboundHandler]).servletResponse = response
      //      if (channel.isConnected) {
      //        response.setStatus(HttpServletResponse.SC_OK)
      //        response.setHeader(ResponseCompleted.name, "false")
      //        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
      //        response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED)
      //        //        response.setHeader(HttpHeaders.Names.TRAILER, "checksum")
      //        // Initiate chunked encoding by flushing the headers.
      //        response.getOutputStream.flush()
      //
      //        //
      //        //        response.setStatus(200)
      //        //        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
      //        //        response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED)
      //        //        response.setHeader("checksum", "checksum")
      //        //        response.setContentLength(0)
      //
      //        channel.write(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer))
      //      } else {
      //        channel.close()
      //        writeErrorResponse(response)
      //      }
      //    }
    }
  }

  def writeErrorResponse(response: HttpServletResponse) {
    response.setHeader(ResponseCompleted.name, "true")
    response.setStatus(200)
    response.setContentType("application/octet-stream")
    response.setContentLength("HTTP/1.1 400 Can't establish connection\r\n\r\n".getBytes("UTF-8").length)
    response.getOutputStream.write("HTTP/1.1 400 Can't establish connection\r\n\r\n".getBytes("UTF-8"))
    response.getOutputStream.flush()
  }

}
