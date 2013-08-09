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
import javax.servlet.ServletConfig
import javax.servlet.annotation.WebServlet

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

  override def service(request: HttpServletRequest, response: HttpServletResponse) {
    if (StringUtils.isEmpty(request.getRequestedSessionId) && request.getSession(false) == null) {
      request.getSession(true)
      logger.debug(s"Created session: ${request.getSession.getId} for request: ${request}")
    }

    require(StringUtils.isNotEmpty(request.getSession.getId), "Session have not been created, server error.")

    val proxyRequestBuffer: Array[Byte] = proxyRequest(request)
    val proxyRequestChannelBuffer = ChannelBuffers.wrappedBuffer(proxyRequestBuffer)
    //    logger.debug(s"Decrypted proxy request:${Utils.formatMessage(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer))}")

    val proxyHost = Host(request.getHeader(ProxyHostHeader.name))
    val channelKey: ChannelKey = ChannelKey(request.getSession.getId, proxyHost)

    //User HTTP for unset flag just make less data bytes.
    def requestType = try {
      RequestType(request.getHeader(ProxyRequestType.name).toByte)
    } catch {
      case e: Throwable ⇒ HTTP
    }

    requestType match {
      case HTTPS ⇒ httpsProcessor.process(channelKey, proxyRequestBuffer)(request, response)
      case _     ⇒ new HttpClientRequestProcessor(response, channelKey, proxyRequestBuffer).process
    }
  }

  def proxyRequest(request: HttpServletRequest): Array[Byte] = {

    //    val bytes: Array[Byte] = new Array[Byte](request.getInputStream.available())
    //    request.getInputStream.read(bytes)
    val compressedData: Array[Byte] = IOUtils.toByteArray(request.getInputStream)
    //    val compressedData: Array[Byte] = bytes
    val encryptedProxyRequest = Utils.inflate(compressedData)
    try {
      //    logger.debug(s"############Process payload ###############\n${Utils.hexDumpToString(encryptedProxyRequest)}")
      val proxyRequestBuffer: Array[Byte] = encryptor.decrypt(encryptedProxyRequest)
      proxyRequestBuffer
    } catch {
      case e: Throwable ⇒
        {
          logger.error(s"Error ############Process payload ###############\n${Utils.hexDumpToString(compressedData)}", e)
          logger.error(s"Error ############Process payload ###############\n${Utils.hexDumpToString(compressedData)}", e)
        }
        null
    }

  }

  val httpsProcessor = new SocketHttpsRequestProcessor()
  System.setProperty("javax.net.debug", "all")

  private[this] val sockets = scala.collection.mutable.Map[ChannelKey, Socket]()

}

