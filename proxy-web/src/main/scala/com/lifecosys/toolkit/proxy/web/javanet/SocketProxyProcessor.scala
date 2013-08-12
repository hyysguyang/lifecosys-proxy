package com.lifecosys.toolkit.proxy.web.javanet

import com.lifecosys.toolkit.proxy.web._
import java.net.Socket
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }
import org.jboss.netty.handler.codec.http.HttpHeaders
import com.lifecosys.toolkit.proxy._
import com.typesafe.scalalogging.slf4j.Logging

abstract class SocketProxyProcessor extends ProxyProcessor with Logging {
  def connect(channelKey: ChannelKey) = {
    val socket = new Socket()
    socket.setKeepAlive(true)
    socket.setTcpNoDelay(true)
    socket.setSoTimeout(120 * 1000)
    socket.connect(channelKey.proxyHost.socketAddress, 30 * 1000)
    socket
  }

  /**
   * Block request and use chunked response to relay proxy response to client.
   */
  def createConnection(connectedCallback: (Socket) ⇒ Unit)(implicit request: HttpServletRequest, response: HttpServletResponse) = {
    val socket = connect(parseChannelKey(request))
    request.getSession(false).setAttribute(SESSION_KEY_ENDPOINT, socket)
    initializeChunkedResponse(response)

    connectedCallback(socket)

    Utils.iterateStream(socket.getInputStream) {
      (data, length) ⇒
        logger.debug(s"[${socket}] - Receive data: ${val tempData = new Array[Byte](length); data.copyToArray(tempData); Utils.hexDumpToString(tempData)}")
        response.getOutputStream.write(data, 0, length)
        response.getOutputStream.flush()
    }

    logger.info("Request completed, close socket, remove task.")
    socket.close()
    request.getSession(false).invalidate()
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

class SocketHttpProxyProcessor extends SocketProxyProcessor {
  def process(proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse) {
    createConnection { socket ⇒
      relayProxyRequest(socket, proxyRequestBuffer)
    }
  }

}

class SocketHttpsProxyProcessor extends SocketProxyProcessor {
  def process(proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse) {
    request.getSession(false).getAttribute(SESSION_KEY_ENDPOINT) match {
      case socket: Socket ⇒ relayProxyRequest(socket, proxyRequestBuffer)
      case _ ⇒ createConnection { socket ⇒
        response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
        response.getOutputStream.flush()
      }
    }

  }
}
