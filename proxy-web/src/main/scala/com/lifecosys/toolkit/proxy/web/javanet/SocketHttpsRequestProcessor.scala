package com.lifecosys.toolkit.proxy.web.javanet;

import com.lifecosys.toolkit.proxy.{ Utils, ResponseCompleted, ChannelKey };
import com.lifecosys.toolkit.proxy.web.{ Message, RequestProcessor }
import java.net.Socket
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import org.jboss.netty.handler.codec.http.HttpHeaders
import java.nio.ByteBuffer
import java.io.{ ByteArrayInputStream, InputStream }
import com.lifecosys.toolkit.proxy
import com.typesafe.scalalogging.slf4j.Logging
import org.apache.http.impl.client.HttpClients
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.io.{ DefaultHttpRequestParser, HttpTransportMetricsImpl, SessionInputBufferImpl }
import org.apache.http.{ HttpEntity, HttpHost }
import org.apache.commons.io.IOUtils

class SocketHttpsRequestProcessor extends RequestProcessor {
  val tasks = scala.collection.mutable.Map[ChannelKey, SocketTask]()
  case class SocketTask(channelKey: ChannelKey) {
    val socket = connect
    def connect = {
      val socket = new Socket()
      socket.setKeepAlive(true)
      socket.setTcpNoDelay(true)
      socket.setSoTimeout(1000 * 1000)
      socket.connect(channelKey.proxyHost.socketAddress, 30 * 1000)
      socket
    }

    def submit(message: Message): Unit = {

      message.response.setStatus(HttpServletResponse.SC_OK)
      message.response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
      message.response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
      message.response.setHeader(ResponseCompleted.name, "true")
      // Initiate chunked encoding by flushing the headers.
      message.response.getOutputStream.flush()

      val socketOutput = socket.getOutputStream
      val array: Array[Byte] = message.proxyRequestBuffer
      logger.error(s"[${Thread.currentThread()} | ${message.request.getSession.getId} | $socket] - Process payload: ${Utils.hexDumpToString(array)}")
      socketOutput.write(array)
      socketOutput.flush()
      val socketInput = socket.getInputStream

      //        message.response.getOutputStream.write(socketInput.read())
      //        message.response.setContentLength(socketInput.available()+1)
      //        logger.debug(s"[${message.request.getSession.getId} | $socket] - 1 Received data : ${socketInput.available()}")
      //        message.response.getOutputStream.write(IOUtils.toByteArray(socketInput, socketInput.available()))
      //        logger.debug(s"[${message.request.getSession.getId} | $socket] - 2 Received data : ${socketInput.available()}")
      //        message.response.getOutputStream.write(IOUtils.toByteArray(socketInput, socketInput.available()))
      //        logger.debug(s"[${message.request.getSession.getId} | $socket] - 3 Received data : ${socketInput.available()}")

      var record = readDataRecord(message, socket.getInputStream)
      var length = record.length
      message.response.getOutputStream.write(record)
      message.response.getOutputStream.flush()
      logger.error(s"Writing response: ${record.length}")
      while (socketInput.available() != 0) {
        logger.error(s"[${message.request.getSession.getId} | $socket] - Reading continue data record: ${socketInput.available()}")
        record = readDataRecord(message, socket.getInputStream)
        try {
          message.response.getOutputStream.write(record)
          message.response.getOutputStream.flush()
          logger.error(s"Writing response: ${record.length}")
        } catch {
          case e: Throwable ⇒ logger.error("Error", e)
        }
        length += record.length
      }

      if (record(0) == 0x14) {
        record = readDataRecord(message, socket.getInputStream)
        message.response.getOutputStream.write(record)
        message.response.getOutputStream.flush()
        logger.error(s"Writing response: ${record.length}")
        length += record.length
      }
      logger.error(s"Writing total response: ${length}")
      //        message.response.setContentLength(length)
      message.response.getOutputStream.flush()

    }

  }
  def process(channelKey: ChannelKey, proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse) {
    //      def isCloseRecord(buffer:ChannelBuffer) = buffer.readableBytes() > 5 &&
    //        (buffer.getShort(3) + 5) == buffer.readableBytes() && buffer.getByte(0) ==0x15

    //      def isCloseRecord(buffer:ByteBuffer) = buffer.capacity() > 5 &&
    def isCloseRecord(buffer: Array[Byte], length: Int) = length > 5 &&
      length == ByteBuffer.wrap(buffer, 3, 2).getShort + 5 && buffer(0) == 0x15

    def connect {
      val task = SocketTask(channelKey)
      tasks += channelKey -> task
      val servletRequest = request
      val servletResponse = response
      servletResponse.setStatus(HttpServletResponse.SC_OK)
      servletResponse.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
      servletResponse.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
      servletResponse.setHeader(ResponseCompleted.name, "true")
      // Initiate chunked encoding by flushing the headers.
      servletResponse.getOutputStream.flush()
      servletResponse.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
      servletResponse.getOutputStream.flush()

      val stream: InputStream = task.socket.getInputStream

      val buffer = new Array[Byte](proxy.DEFAULT_BUFFER_SIZE)
      var length = 0
      var isClosed = false
      def read = {
        length = stream.read(buffer)
        length != -1
      }
      while (!isClosed && read) {
        //          logger.debug(s"[${Thread.currentThread()} | ${servletRequest.getSession(false).getId} | ${task.socket}}] - Receive data: ${val tempData=new Array[Byte](length);buffer.copyToArray(tempData);Utils.hexDumpToString(tempData)}")
        logger.debug(s"[${task.socket}] - Receive data: ${val tempData = new Array[Byte](length); buffer.copyToArray(tempData); Utils.hexDumpToString(tempData)}")
        servletResponse.getOutputStream.write(buffer, 0, length)
        servletResponse.getOutputStream.flush()
        isClosed = isCloseRecord(buffer, length)
      }

      logger.info("Request completed, close socket, remove task.")
      task.socket.close()
      tasks -= channelKey
    }

    tasks.get(channelKey) match {
      case Some(task) ⇒ {
        logger.debug(s"[${Thread.currentThread()} | ${request.getSession.getId} | ${task.socket}}] - Process payload: ${Utils.hexDumpToString(proxyRequestBuffer)}")
        val socketOutput = task.socket.getOutputStream
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
      case None ⇒ connect
    }

  }

}

class HttpClientRequestProcessor(response: HttpServletResponse, channelKey: ChannelKey, proxyRequestBuffer: Array[Byte]) extends Logging {

  val httpClient = HttpClients.custom.setDefaultRequestConfig(RequestConfig.custom().setRedirectsEnabled(false).build()).build()

  def process {
    //    val httpclient: CloseableHttpClient = HttpClients.createDefault()
    //val clientContext=HttpClientContext.create()
    //    clientContext.setRequestConfig(RequestConfig.custom().setRedirectsEnabled(false).build())
    val buffer = new SessionInputBufferImpl(new HttpTransportMetricsImpl(), 1024, 2048, null, null);
    buffer.bind(new ByteArrayInputStream(proxyRequestBuffer))
    val parser = new DefaultHttpRequestParser(buffer)
    val proxyRequest = parser.parse()

    logger.debug(s"#######################$proxyRequest")

    val httpHost = new HttpHost(channelKey.proxyHost.host, channelKey.proxyHost.port)
    val proxyResponse = httpClient.execute(httpHost, proxyRequest)
    try {
      response.setStatus(proxyResponse.getStatusLine.getStatusCode)
      val entity: HttpEntity = proxyResponse.getEntity
      if (entity.getContentType != null) response.setContentType(entity.getContentType.getValue)
      if (entity.getContentLength >= 0) response.setContentLength(entity.getContentLength.toInt)
      if (entity.getContentEncoding != null) response.setCharacterEncoding(entity.getContentEncoding.getValue)
      proxyResponse.getAllHeaders.toList.foreach {
        header ⇒ response.addHeader(header.getName, header.getValue)
      }
      val content = entity.getContent
      IOUtils.copy(content, response.getOutputStream)
      response.getOutputStream.flush()
      IOUtils.closeQuietly(entity.getContent)
    } finally {
      proxyResponse.close
    }
  }

}
