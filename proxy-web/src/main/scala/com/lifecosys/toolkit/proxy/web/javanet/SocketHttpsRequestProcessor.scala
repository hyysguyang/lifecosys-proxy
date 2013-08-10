package com.lifecosys.toolkit.proxy.web.javanet;

import com.lifecosys.toolkit.proxy.{ Utils, ResponseCompleted, ChannelKey };
import com.lifecosys.toolkit.proxy.web.RequestProcessor
import java.net.Socket
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import org.jboss.netty.handler.codec.http.HttpHeaders
import java.nio.ByteBuffer
import java.io.InputStream
import com.lifecosys.toolkit.proxy
import com.typesafe.scalalogging.slf4j.Logging

abstract class SocketRequestProcessor extends RequestProcessor {
  def connectWithSocket(channelKey: ChannelKey) = {
    val socket = new Socket()
    socket.setKeepAlive(true)
    socket.setTcpNoDelay(true)
    socket.setSoTimeout(120 * 1000)
    socket.connect(channelKey.proxyHost.socketAddress, 30 * 1000)
    socket
  }

  def relayProxyRequest(socket: Socket, proxyRequestBuffer: Array[Byte]) {
    val socketOutput = socket.getOutputStream
    try {
      socketOutput.write(proxyRequestBuffer)
      socketOutput.flush()
    } catch {
      case e: Throwable ⇒ {
        logger.warn(s"Write proxy request failure:${Utils.hexDumpToString(proxyRequestBuffer)}", e)
        //TODO Close browser connection..
      }
    }
  }

}

class SocketHttpRequestProcessor extends SocketRequestProcessor with Logging {
  def process(channelKey: ChannelKey, proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse) {
    val socket = connectWithSocket(channelKey)
    response.setStatus(HttpServletResponse.SC_OK)
    response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
    response.setHeader(ResponseCompleted.name, "true")
    // Initiate chunked encoding by flushing the headers.
    response.getOutputStream.flush()

    relayProxyRequest(socket, proxyRequestBuffer)

    val stream: InputStream = socket.getInputStream
    val buffer = new Array[Byte](proxy.DEFAULT_BUFFER_SIZE)
    var length = 0
    def read = {
      length = stream.read(buffer)
      length != -1
    }
    while (read) {
      //          logger.debug(s"[${Thread.currentThread()} | ${servletRequest.getSession(false).getId} | ${task.socket}}] - Receive data: ${val tempData=new Array[Byte](length);buffer.copyToArray(tempData);Utils.hexDumpToString(tempData)}")
      logger.debug(s"[${socket}] - Receive data: ${val tempData = new Array[Byte](length); buffer.copyToArray(tempData); Utils.hexDumpToString(tempData)}")
      response.getOutputStream.write(buffer, 0, length)
      response.getOutputStream.flush()
    }

    logger.info("Request completed, close socket, remove task.")
    socket.close()
    request.getSession(false).invalidate()
  }

}

class SocketHttpsRequestProcessor extends SocketRequestProcessor {
  def process(channelKey: ChannelKey, proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse) {
    //      def isCloseRecord(buffer:ChannelBuffer) = buffer.readableBytes() > 5 &&
    //        (buffer.getShort(3) + 5) == buffer.readableBytes() && buffer.getByte(0) ==0x15

    //      def isCloseRecord(buffer:ByteBuffer) = buffer.capacity() > 5 &&
    def isCloseRecord(buffer: Array[Byte], length: Int) = length > 5 &&
      length == ByteBuffer.wrap(buffer, 3, 2).getShort + 5 && buffer(0) == 0x15

    /**
     * Block request and use chunked response to relay proxy response to client.
     */
    def connectProcess {
      val socket = connectWithSocket(channelKey)
      request.getSession(false).setAttribute("socket", socket)

      response.setStatus(HttpServletResponse.SC_OK)
      response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
      response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
      response.setHeader(ResponseCompleted.name, "true")
      // Initiate chunked encoding by flushing the headers.
      response.getOutputStream.flush()
      response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
      response.getOutputStream.flush()

      val stream: InputStream = socket.getInputStream

      val buffer = new Array[Byte](proxy.DEFAULT_BUFFER_SIZE)
      var length = 0
      var isClosed = false
      def read = {
        length = stream.read(buffer)
        length != -1
      }
      while (!isClosed && read) {
        //          logger.debug(s"[${Thread.currentThread()} | ${servletRequest.getSession(false).getId} | ${task.socket}}] - Receive data: ${val tempData=new Array[Byte](length);buffer.copyToArray(tempData);Utils.hexDumpToString(tempData)}")
        logger.debug(s"[${socket}] - Receive data: ${val tempData = new Array[Byte](length); buffer.copyToArray(tempData); Utils.hexDumpToString(tempData)}")
        response.getOutputStream.write(buffer, 0, length)
        response.getOutputStream.flush()
        isClosed = isCloseRecord(buffer, length)
      }

      logger.info("Request completed, close socket, remove task.")
      socket.close()
      request.getSession(false).invalidate()
    }

    request.getSession(false).getAttribute("socket") match {
      case socket: Socket ⇒ {
        logger.debug(s"Process payload: ${Utils.hexDumpToString(proxyRequestBuffer)}")
        relayProxyRequest(socket, proxyRequestBuffer)
      }
      case _ ⇒ connectProcess
    }

  }

}
