package com.lifecosys.toolkit.proxy

import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import org.jboss.netty.handler.codec.http.HttpHeaders
import com.typesafe.scalalogging.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

/**
 *
 *
 * @author Young Gu
 * @version 1.0 8/9/13 1:50 PM
 */
package object web {
  lazy val logger = Logger(LoggerFactory getLogger getClass.getName)

  val SESSION_KEY_ENDPOINT = "com.lifecosys.toolkit.proxy.web.endpoint"

  def parseChannelKey(request: HttpServletRequest) = {
    val encodedProxyHost = request.getHeader(ProxyHostHeader.name)
    //    val proxyHost = Host(new String(encryptor.decrypt(base64.decode(encodedProxyHost)), UTF8))
    val proxyHost = Host(new String(encryptor.decrypt(base64.decode(encodedProxyHost)), UTF8))
    ChannelKey(request.getHeader(ProxyRequestID.name), proxyHost)
  }

  def writeResponse(request: HttpServletRequest, response: HttpServletResponse, data: Array[Byte]) {
    val encrypt: Array[Byte] = encryptor.encrypt(data)
    //    logger.debug(s"Write encrypt response: ${Utils.hexDumpToString(encrypt)}")
    //Write the length header of this data packet, include response data length and the length header length
    response.getOutputStream.write(ByteBuffer.allocate(2).putShort((encrypt.length + 2).toShort).array())
    response.getOutputStream.write(encrypt)
    response.getOutputStream.flush
  }

  def writeErrorResponse(response: HttpServletResponse) {
    response.setStatus(200)
    response.setContentType("application/octet-stream")
    val error = "HTTP/1.1 400 Can't establish connection\r\n\r\n".getBytes("UTF-8")
    response.setContentLength(error.length)
    response.getOutputStream.write(error)
    response.getOutputStream.flush()
  }

  def initializeChunkedResponse(response: HttpServletResponse) {
    response.setStatus(HttpServletResponse.SC_OK)
    response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
    // Initiate chunked encoding by flushing the headers.
    response.getOutputStream.flush()

  }

}
