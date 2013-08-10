package com.lifecosys.toolkit.proxy

import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import org.jboss.netty.handler.codec.http.HttpHeaders

/**
 *
 *
 * @author Young Gu
 * @version 1.0 8/9/13 1:50 PM
 */
package object web {

  val SESSION_KEY_ENDPOINT = "com.lifecosys.toolkit.proxy.web.endpoint"

  def parseChannelKey(request: HttpServletRequest) = ChannelKey(request.getSession.getId, Host(request.getHeader(ProxyHostHeader.name)))

  def writeErrorResponse(response: HttpServletResponse) {
    response.setHeader(ResponseCompleted.name, "true")
    response.setStatus(200)
    response.setContentType("application/octet-stream")
    response.setContentLength("HTTP/1.1 400 Can't establish connection\r\n\r\n".getBytes("UTF-8").length)
    response.getOutputStream.write("HTTP/1.1 400 Can't establish connection\r\n\r\n".getBytes("UTF-8"))
    response.getOutputStream.flush()
  }

  def initializeChunkedResponse(response: HttpServletResponse) {
    response.setStatus(HttpServletResponse.SC_OK)
    response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
    response.setHeader(ResponseCompleted.name, "true")
    // Initiate chunked encoding by flushing the headers.
    response.getOutputStream.flush()
  }

}
