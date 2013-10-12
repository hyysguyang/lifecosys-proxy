package com.lifecosys.toolkit.proxy.web

import javax.servlet.http.{ HttpServletResponse, HttpServletRequest, HttpServlet }
import org.apache.commons.io.IOUtils
import org.jboss.netty.logging.{ Slf4JLoggerFactory, InternalLoggerFactory }
import com.typesafe.scalalogging.slf4j.Logging
import org.apache.commons.lang3.StringUtils
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import com.lifecosys.toolkit.proxy._
import com.lifecosys.toolkit.proxy.RequestType
import javax.servlet.annotation.WebServlet
import scala.util.Try
import com.lifecosys.toolkit.proxy.web.netty.{ NettyTaskSupport, HttpsNettyProxyProcessor, HttpNettyProxyProcessor }
import java.util.concurrent.Executors

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
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  Security.addProvider(new BouncyCastleProvider)

  //  val httpProcessor = new SocketHttpProxyProcessor()
  //  val httpsProcessor = new SocketHttpsProxyProcessor()
  val httpProcessor = new HttpNettyProxyProcessor()
  val httpsProcessor = new HttpsNettyProxyProcessor()

  override def service(request: HttpServletRequest, response: HttpServletResponse) {
    //    createSessionIfNecessary(request)

    logger.error(s"----->Receive request: $request ")

    //    require(StringUtils.isNotBlank(request.getHeader(ProxyRequestID.name)), "Proxy request ID must not be empty.")
    val proxyRequestBuffer: Array[Byte] = parseProxyRequest(request)
        logger.debug(s">>>>>>>>>>>>Process proxy request:\n${Utils.hexDumpToString(proxyRequestBuffer)}")

    proxyProcessor(request).process(proxyRequestBuffer)(request, response)
  }

  def proxyProcessor(request: HttpServletRequest) = {
    val requestType = Try(RequestType(request.getHeader(ProxyRequestType.name).toByte)).getOrElse(HTTP)
    requestType match {
      case HTTPS ⇒ httpsProcessor
      case _     ⇒ new HttpNettyProxyProcessor()
    }
  }

  def createSessionIfNecessary(request: HttpServletRequest) {
    if (StringUtils.isEmpty(request.getRequestedSessionId) && request.getSession(false) == null) {
      request.getSession(true)
      logger.info(s"Created session: ${request.getSession.getId} for request: ${request}")
    }

    require(StringUtils.isNotEmpty(request.getSession.getId), "Session have not been created, server error.")
  }

  def parseProxyRequest(request: HttpServletRequest): Array[Byte] = {
    val compressedData: Array[Byte] = IOUtils.toByteArray(request.getInputStream)
    try {
      encryptor.decrypt(compressedData)
    } catch {
      case e: Throwable ⇒
        logger.error(s"Parse proxy request from payload:\n${Utils.hexDumpToString(compressedData)}", e)
        throw e
    }

  }
}

