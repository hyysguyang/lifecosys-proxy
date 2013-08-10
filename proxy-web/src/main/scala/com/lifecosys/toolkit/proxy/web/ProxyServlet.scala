package com.lifecosys.toolkit.proxy.web

import javax.servlet.http.{ HttpServletResponse, HttpServletRequest, HttpServlet }
import org.jboss.netty.buffer._
import org.apache.commons.io.IOUtils
import org.jboss.netty.logging.{ Slf4JLoggerFactory, InternalLoggerFactory }
import com.typesafe.scalalogging.slf4j.Logging
import org.apache.commons.lang3.StringUtils
import java.net.Socket
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import com.lifecosys.toolkit.proxy.web.javanet.{ SocketHttpsRequestProcessor, HttpClientRequestProcessor }
import com.lifecosys.toolkit.proxy._
import com.lifecosys.toolkit.proxy.ChannelKey
import com.lifecosys.toolkit.proxy.RequestType
import javax.servlet.{ AsyncEvent, AsyncListener, ServletConfig }
import javax.servlet.annotation.WebServlet
import com.lifecosys.toolkit.proxy.web.netty.{ NettyHttpRequestProcessor, NettyHttpsRequestProcessor }

/**
 *
 *
 * @author Young Gu
 * @version 1.0 6/21/13 10:28 AM
 */

@WebServlet(
  name = "proxyServlet",
  urlPatterns = Array("/proxy"),
  asyncSupported = true,
  loadOnStartup = 1)
class ProxyServlet extends HttpServlet with Logging {
  System.setProperty("javax.net.debug", "all")
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  Security.addProvider(new BouncyCastleProvider)

  val httpProcessor = new NettyHttpRequestProcessor()
  val httpsProcessor = new NettyHttpsRequestProcessor()

  override def service(request: HttpServletRequest, response: HttpServletResponse) {
    createSessionIfNecessary(request)

    val proxyRequestBuffer: Array[Byte] = parseProxyRequest(request)
    logger.debug(s"[${request.getSession.getId}] - Process proxy request:\n${Utils.hexDumpToString(proxyRequestBuffer)}")

    val proxyHost = Host(request.getHeader(ProxyHostHeader.name))
    val channelKey = ChannelKey(request.getSession.getId, proxyHost)

    //User HTTP for unset flag just make less data bytes.
    def requestType = try {
      RequestType(request.getHeader(ProxyRequestType.name).toByte)
    } catch {
      case e: Throwable ⇒ HTTP
    }

    requestType match {
      case HTTPS ⇒ httpsProcessor.process(channelKey, proxyRequestBuffer)(request, response)
      case _     ⇒ httpProcessor.process(channelKey, proxyRequestBuffer)(request, response)
    }
  }

  def createSessionIfNecessary(request: HttpServletRequest) {
    if (StringUtils.isEmpty(request.getRequestedSessionId) && request.getSession(false) == null) {
      request.getSession(true)
      logger.debug(s"Created session: ${request.getSession.getId} for request: ${request}")
    }

    require(StringUtils.isNotEmpty(request.getSession.getId), "Session have not been created, server error.")
  }

  def parseProxyRequest(request: HttpServletRequest): Array[Byte] = {
    val compressedData: Array[Byte] = IOUtils.toByteArray(request.getInputStream)
    val encryptedProxyRequest = Utils.inflate(compressedData)
    try {
      val proxyRequestBuffer: Array[Byte] = encryptor.decrypt(encryptedProxyRequest)
      proxyRequestBuffer
    } catch {
      case e: Throwable ⇒
        logger.error(s"Parse proxy request from payload:\n${Utils.hexDumpToString(compressedData)}", e)
        throw e
    }

  }
}

