/*
 * ===Begin Copyright Notice===
 *
 *  NOTICE
 *
 *  THIS SOFTWARE IS THE PROPERTY OF AND CONTAINS CONFIDENTIAL INFORMATION OF
 *  LIFECOSYS AND/OR ITS AFFILIATES OR SUBSIDIARIES AND SHALL NOT BE DISCLOSED
 *  WITHOUT PRIOR WRITTEN PERMISSION. LICENSED CUSTOMERS MAY COPY AND ADAPT
 *  THIS SOFTWARE FOR THEIR OWN USE IN ACCORDANCE WITH THE TERMS OF THEIR
 *  SOFTWARE LICENSE AGREEMENT. ALL OTHER RIGHTS RESERVED.
 *
 *  (c) COPYRIGHT 2013 LIFECOCYS. ALL RIGHTS RESERVED. THE WORD AND DESIGN
 *  MARKS SET FORTH HEREIN ARE TRADEMARKS AND/OR REGISTERED TRADEMARKS OF
 *  LIFECOSYS AND/OR ITS AFFILIATES AND SUBSIDIARIES. ALL RIGHTS RESERVED.
 *  ALL LIFECOSYS TRADEMARKS LISTED HEREIN ARE THE PROPERTY OF THEIR RESPECTIVE
 *  OWNERS.
 *
 *  ===End Copyright Notice===
 */

package com.lifecosys.toolkit.functional

import org.apache.http.client.fluent.{ Executor, Request }
import org.apache.http.{ NoHttpResponseException, HttpHost }
import java.net.InetSocketAddress
import org.apache.http.conn.scheme.Scheme
import org.apache.http.conn.ssl.SSLSocketFactory
import org.jboss.netty.channel.ChannelException
import org.jboss.netty.logging.{ Slf4JLoggerFactory, InternalLoggerFactory }
import ProxyTestUtils._
import com.lifecosys.toolkit.proxy._
import com.typesafe.config.ConfigFactory
import org.jboss.netty.handler.codec.http.{ HttpMethod, HttpVersion, DefaultHttpRequest }
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import scala.util.Try
import org.scalatest.{ BeforeAndAfter, FunSuite }
import org.apache.http.impl.client.{ CloseableHttpClient, DefaultHttpRequestRetryHandler, HttpClients }
import org.apache.http.config.SocketConfig
import org.apache.http.client.methods.{ CloseableHttpResponse, HttpGet }

/**
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 12/2/12 12:56 AM
 */

object ProxyTestUtils {

  Executor.registerScheme(new Scheme("https", 443, new SSLSocketFactory(Utils.trustAllSSLContext)))

  Security.addProvider(new BouncyCastleProvider)
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)

  def request(url: String): Request = {
    Request.Get(url).socketTimeout(60 * 1000)
  }

  def zip(proxyContent: Any): String = {
    proxyContent.toString.filter(_.isWhitespace).replace("\n", "").replace("\r", "")
  }

  def createHttpClient(proxyPort: Int) = HttpClients.custom()
    .setRetryHandler(new DefaultHttpRequestRetryHandler(0, false))
    .setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(60 * 1000).build())
    .setSSLSocketFactory(new SSLSocketFactory(Utils.trustAllSSLContext, SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER))
    .setProxy(new HttpHost("localhost", proxyPort))
    .build()

  def createProxyConfig(bindPort: Int = 8080,
                        chainedPort: Option[Int] = None,
                        isServerSSLEnable: Boolean = false,
                        isClientSSLEnable: Boolean = false,
                        isLocalProxy: Boolean = true,
                        chainProxyManager: ChainProxyManager = new DefaultChainProxyManager) = {

    val chainProxy = chainedPort match {
      case Some(port) ⇒ """{host = "localhost:%s type ="net"}""".format(port + "\"\n")
      case None       ⇒ ""
    }

    val configString = s"""
      |port = $bindPort
      |local=$isLocalProxy
      |chain-proxy = [
      |  $chainProxy
      |]
      |proxy-server{
      |    thread {
      |        corePoolSize = 10
      |        maximumPoolSize = 30
      |    }
      |    ssl {
      |           enabled = $isServerSSLEnable
      |           keystore-password = "killccp"
      |           keystore-path = "/binary/keystore/lifecosys-proxy-server-keystore.jks"
      |           trust-keystore-path = "/binary/keystore/lifecosys-proxy-client-for-server-trust-keystore.jks"
      |    }
      |}
      |
      |proxy-server-to-remote{
      |    thread {
      |        corePoolSize = 10
      |        maximumPoolSize = 30
      |    }
      |    ssl {
      |           enabled = $isClientSSLEnable
      |           keystore-password = "killccp"
      |           keystore-path = "/binary/keystore/lifecosys-proxy-client-keystore.jks"
      |           trust-keystore-path = "/binary/keystore/lifecosys-proxy-server-for-client-trust-keystore.jks"
      |    }
      |}
    """.stripMargin

    val config = ConfigFactory.parseString(configString).withFallback(ConfigFactory.load())

    new DefaultStaticCertificationProxyConfig(Some(config)) {
      override def getChainProxyManager: ChainProxyManager = chainProxyManager
    }

  }

}

trait BaseFunSuite extends FunSuite {
  def doAssert(response: CloseableHttpResponse) {
    assert(response.getStatusLine.getStatusCode === 200)
  }
}

class SimpleProxyTest extends BaseFunSuite with BeforeAndAfter {

  var proxy: ProxyServer = null
  var httpClient: Option[CloseableHttpClient] = None

  before {
    InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
    Security.addProvider(new BouncyCastleProvider)
  }

  after {
    proxy shutdown

    proxy = null

    httpClient.foreach(_.close())
  }

  test("Shutdown when can't bind port.") {
    proxy = ProxyServer(createProxyConfig(bindPort = 19080))
    val result = Try {
      proxy start

      proxy shutdown

      proxy = ProxyServer(createProxyConfig(bindPort = 19080))
      proxy start

      ProxyServer(createProxyConfig(bindPort = 19080)) start
    }
    assert(result.failed.get.isInstanceOf[ChannelException] === true)
  }

  test("Access simple page") {

    proxy = ProxyServer(createProxyConfig(bindPort = 19081))

    proxy start

    httpClient = Some(createHttpClient(19081))

    doAssert(httpClient.get.execute(new HttpGet("http://www.apple.com/")))
  }

  test("Access another simple page") {
    proxy = ProxyServer(createProxyConfig(bindPort = 19082))

    proxy start

    httpClient = Some(createHttpClient(19082))

    doAssert(httpClient.get.execute(new HttpGet("http://www.apple.com/")))

  }

  test("Access https page") {
    proxy = ProxyServer(createProxyConfig(bindPort = 19083))

    proxy start

    httpClient = Some(createHttpClient(19083))

    doAssert(httpClient.get.execute(new HttpGet("https://developer.apple.com/")))

  }

}

class ChainedProxyTest extends BaseFunSuite with BeforeAndAfter {
  object gfwChainProxyManager extends GFWChainProxyManager
  var proxy: ProxyServer = null
  var chainProxy: ProxyServer = null

  var httpClient: Option[CloseableHttpClient] = None

  before {
    InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
    Security.addProvider(new BouncyCastleProvider)
  }

  after {

    proxy shutdown

    if (chainProxy != null) chainProxy shutdown

    proxy = null
    chainProxy = null

    httpClient.foreach(_.close())

  }

  test("Access http web page via chained proxy") {
    proxy = ProxyServer(createProxyConfig(bindPort = 19090, chainedPort = Some(19091)))
    chainProxy = ProxyServer(createProxyConfig(bindPort = 19091, isLocalProxy = false))

    chainProxy start

    proxy start

    httpClient = Some(createHttpClient(19090))

    doAssert(httpClient.get.execute(new HttpGet("http://www.baidu.com/")))

  }

  test("Access http web page via chained proxy when bypass chained proxy.") {
    proxy = ProxyServer(createProxyConfig(bindPort = 19092, chainProxyManager = gfwChainProxyManager))
    proxy start

    httpClient = Some(createHttpClient(19092))

    doAssert(httpClient.get.execute(new HttpGet("http://www.yahoo.com/")))
  }

  test("Access https web page via chained proxy when bypass chained proxy.") {
    proxy = ProxyServer(createProxyConfig(bindPort = 19093, chainedPort = Some(19094), chainProxyManager = gfwChainProxyManager))
    proxy start

    httpClient = Some(createHttpClient(19093))

    doAssert(httpClient.get.execute(new HttpGet("https://developer.apple.com/")))

  }

  test("Access http web page via chained proxy when bypass chained proxy with SSL support.") {
    proxy = ProxyServer(createProxyConfig(bindPort = 19095, chainedPort = Some(19096), isClientSSLEnable = true, chainProxyManager = gfwChainProxyManager))
    proxy start

    httpClient = Some(createHttpClient(19095))

    doAssert(httpClient.get.execute(new HttpGet("http://www.yahoo.com/")))

  }

  test("Access https web page via chained proxy when bypass chained proxy with SSL support.") {
    proxy = ProxyServer(createProxyConfig(bindPort = 19096, chainedPort = Some(19097), isClientSSLEnable = true, chainProxyManager = gfwChainProxyManager))
    proxy start

    httpClient = Some(createHttpClient(19096))

    doAssert(httpClient.get.execute(new HttpGet("https://developer.apple.com/")))

  }

  test("Access web page via unavailable chained proxy") {
    proxy = ProxyServer(createProxyConfig(bindPort = 19098, chainedPort = Some(10)))

    proxy.start

    httpClient = Some(createHttpClient(19098))

    val result = Try((httpClient.get.execute(new HttpGet("http://www.yahoo.com/"))))

    assert(result.isFailure === true)
    assert(result.failed.get.isInstanceOf[NoHttpResponseException] === true)

  }

  test("Access https web page via chained proxy.") {
    proxy = ProxyServer(createProxyConfig(bindPort = 19099, chainedPort = Some(19100)))
    chainProxy = ProxyServer(createProxyConfig(bindPort = 19100, isLocalProxy = false))

    chainProxy start

    proxy start

    httpClient = Some(createHttpClient(19099))

    doAssert(httpClient.get.execute(new HttpGet("https://developer.apple.com/")))

  }

  test("Access http web page via chained proxy with SSL support.") {
    proxy = ProxyServer(createProxyConfig(bindPort = 19101, chainedPort = Some(19102), isClientSSLEnable = true))
    chainProxy = ProxyServer(createProxyConfig(bindPort = 19102, isLocalProxy = false, isServerSSLEnable = true))

    chainProxy start

    proxy start

    httpClient = Some(createHttpClient(19101))

    doAssert(httpClient.get.execute(new HttpGet("http://www.yahoo.com/")))

  }

  test("Access https web page via chained proxy with SSL support.") {
    proxy = ProxyServer(createProxyConfig(bindPort = 19103, chainedPort = Some(19104), isClientSSLEnable = true))
    chainProxy = ProxyServer(createProxyConfig(bindPort = 19104, isLocalProxy = false, isServerSSLEnable = true))

    chainProxy start

    proxy start

    httpClient = Some(createHttpClient(19103))

    doAssert(httpClient.get.execute(new HttpGet("https://developer.apple.com/")))

  }

}

class ChainedProxyManagerTest extends BaseFunSuite with BeforeAndAfter {

  test("Chained proxy manager") {
    val config =
      """
        |chain-proxy = [
        |      {
        |            host ="localhost:8081"
        |            type ="net"
        |       }
        |]
      """.stripMargin

    val request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "http://facebook.com")
    val host = new GFWListChainProxyManager().getConnectHost(request.getUri)(new DefaultStaticCertificationProxyConfig(Some(ConfigFactory.load(ConfigFactory.parseString(config)))))
    assert(host === None)
  }

  test("Chained Proxy Manager") {
    val config =
      """
        |chain-proxy = [
        |      {
        |            host ="localhost:8081"
        |            type ="net"
        |       }
        |]
      """.stripMargin

    val request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "http://facebook.com/")
    val host = new GFWChainProxyManager().getConnectHost(request.getUri)(new DefaultStaticCertificationProxyConfig(Some(ConfigFactory.load(ConfigFactory.parseString(config))))).get

    assert(host.host.socketAddress != new InetSocketAddress("localhost", 8081))
  }

  test("Performance GFW") {

    val request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "http://twitter.com")
    val manager = new GFWChainProxyManager()
    val now = System.currentTimeMillis()
    val host = manager.getConnectHost(request.getUri)(createProxyConfig()).get
    manager.getConnectHost(request.getUri)(createProxyConfig())
    manager.getConnectHost(request.getUri)(createProxyConfig())
    manager.getConnectHost(request.getUri)(createProxyConfig())
    manager.getConnectHost(request.getUri)(createProxyConfig())
    manager.getConnectHost(request.getUri)(createProxyConfig())
    manager.getConnectHost(request.getUri)(createProxyConfig())
    manager.getConnectHost(request.getUri)(createProxyConfig())
    manager.getConnectHost(request.getUri)(createProxyConfig())
    manager.getConnectHost(request.getUri)(createProxyConfig())
    println("################################" + (System.currentTimeMillis() - now))
    assert(host.host.socketAddress != new InetSocketAddress("localhost", 8081))
  }

}