package com.lifecosys.toolkit.proxy

import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import org.jboss.netty.handler.codec.http.HttpHeaders
import com.typesafe.scalalogging.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 *
 *
 * @author Young Gu
 * @version 1.0 8/9/13 1:50 PM
 */
package object web {
  lazy val logger = Logger(LoggerFactory getLogger getClass.getName)

  val SESSION_KEY_ENDPOINT = "com.lifecosys.toolkit.proxy.web.endpoint"

  def parseChannelKey(request: HttpServletRequest) = ChannelKey(request.getSession.getId, Host(request.getHeader(ProxyHostHeader.name)))

  def writeResponse(response: HttpServletResponse, data: Array[Byte]) {
    logger.debug(s"Write response: ${Utils.hexDumpToString(data)}")
    response.getOutputStream.write(Utils.compressAndEncrypt(data))
    response.getOutputStream.flush
  }

  def writeErrorResponse(response: HttpServletResponse) {
    response.setStatus(200)
    response.setContentType("application/octet-stream")
    val error = "HTTP/1.1 400 Can't establish connection\r\n\r\n".getBytes("UTF-8")
    response.setContentLength(error.length)
    response.getOutputStream.write(Utils.compressAndEncrypt(error))
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
