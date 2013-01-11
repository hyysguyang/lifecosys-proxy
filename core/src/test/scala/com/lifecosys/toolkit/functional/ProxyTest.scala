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

import org.apache.http.client.fluent.{Executor, Request}
import org.apache.http.HttpHost
import java.net.InetSocketAddress
import org.apache.http.conn.scheme.Scheme
import org.apache.http.conn.ssl.SSLSocketFactory
import org.junit.{Assert, Test, After, Before}
import org.jboss.netty.channel.ChannelException
import org.jboss.netty.logging.{Slf4JLoggerFactory, InternalLoggerFactory}
import javax.net.ssl.{X509TrustManager, SSLContext}
import java.security.cert.X509Certificate
import collection.mutable
import ProxyTestUtils._
import com.lifecosys.toolkit.proxy._
import com.typesafe.config.ConfigFactory
import org.jboss.netty.handler.codec.http.{HttpMethod, HttpVersion, DefaultHttpRequest}
import scala.Some
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 12/2/12 12:56 AM
 */

object ProxyTestUtils {

  Executor.registerScheme(new Scheme("https", 443, new SSLSocketFactory(createStubSSLClientContext)))

  Utils.installJCEPolicy
  ProxyServer.initialize

  def request(url: String): Request = {
    Request.Get(url).socketTimeout(60 * 1000)
  }

  def zip(proxyContent: Any): String = {
    proxyContent.toString.filter(_.isWhitespace).replace("\n", "").replace("\r", "")
  }


  def createStubSSLClientContext = {
    val clientContext = SSLContext.getInstance("TLS")
    clientContext.init(null, Array(new X509TrustManager {
      def getAcceptedIssuers: Array[X509Certificate] = {
        return new Array[X509Certificate](0)
      }

      def checkClientTrusted(chain: Array[X509Certificate], authType: String) {
        System.err.println("Trust all client" + chain(0).getSubjectDN)
      }

      def checkServerTrusted(chain: Array[X509Certificate], authType: String) {
        System.err.println("Trust all server" + chain(0).getSubjectDN)
      }
    }), null)

    clientContext
  }


  def createProxyConfig(bindPort: Int = 8080,
                        chainedPort: Option[Int] = None,
                        isServerSSLEnable: Boolean = false,
                        isClientSSLEnable: Boolean = false,
                        isLocalProxy: Boolean = true,
                        chainProxyManager: ChainProxyManager = new DefaultChainProxyManager) = {
    new SimpleProxyConfig {
      override val port = bindPort
      override val isLocal = isLocalProxy
      override val chainProxies = chainedPort.map(port => mutable.MutableList[InetSocketAddress](new InetSocketAddress(port))).getOrElse(mutable.MutableList[InetSocketAddress]())
      override val serverSSLEnable = isServerSSLEnable
      override val proxyToServerSSLEnable = isClientSSLEnable

      override def getChainProxyManager: ChainProxyManager = chainProxyManager
    }
  }


  //  def main(args: Array[String]) {
  //    val proxy = ProxyServer(createProxyConfig(chainedPort = Some(8081)))
  //    val chainProxy = ProxyServer(createProxyConfig(bindPort = 8081, isLocalProxy = false))
  //
  //    chainProxy start
  //
  //    proxy start
  //  }


}

class SimpleProxyTest {
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  var proxy: ProxyServer = null

  @Before
  def before() {
    proxy = ProxyServer(createProxyConfig())
  }

  @After
  def after() {
    proxy shutdown

    proxy = null

  }


  @Test(expected = classOf[ChannelException])
  def testShutdown {
    proxy.start
    proxy.shutdown

    proxy = ProxyServer(createProxyConfig())
    proxy.start

    ProxyServer(createProxyConfig()).start

  }


  @Test
  def testSimplePage {
    proxy start

    Assert.assertTrue(request("http://www.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent.toString.length > 0)
  }

  @Test
  def testAnotherSimplePage {
    proxy start

    Assert.assertTrue(request("http://baidu.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent.toString.length > 0)
  }


  @Test
  def testAccessHttps {
    proxy start

    Assert.assertTrue(request("https://developer.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent.toString.length > 0)
  }

}


class ChainedProxyTest {
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  var proxy: ProxyServer = null
  var chainProxy: ProxyServer = null


  @After
  def after() {
    proxy shutdown

    if (chainProxy != null) chainProxy shutdown

    proxy = null
    chainProxy = null

  }


  @Test
  def testAccessViaChainedProxy {
    proxy = ProxyServer(createProxyConfig(chainedPort = Some(8081)))
    chainProxy = ProxyServer(createProxyConfig(bindPort = 8081, isLocalProxy = false))

    chainProxy start

    proxy start

    val proxyContent = request("http://apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    Assert.assertTrue(proxyContent.toString.length > 0)

  }


  def gfwChainProxyManager = new GFWChainProxyManager {

  }

  @Test
  def testAccessViaChainedProxy_bypassChainedProxy {
    proxy = ProxyServer(createProxyConfig(chainedPort = Some(8081), chainProxyManager = gfwChainProxyManager))
    proxy start
    val proxyContent = request("http://apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    Assert.assertTrue(proxyContent.toString.length > 0)
  }

  @Test
  def testAccessViaChainedProxy_forHttps_bypassChainedProxy {
    proxy = ProxyServer(createProxyConfig(chainedPort = Some(8081), chainProxyManager = gfwChainProxyManager))
    proxy start
    val proxyContent = request("https://developer.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    Assert.assertTrue(proxyContent.toString.length > 0)
  }

  @Test
  def testAccessViaChainedProxy_bypassChainedProxy_withSSLSupport {
    proxy = ProxyServer(createProxyConfig(chainedPort = Some(8081), isClientSSLEnable = true, chainProxyManager = gfwChainProxyManager))
    proxy start
    val proxyContent = request("http://apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    Assert.assertTrue(proxyContent.toString.length > 0)
  }

  @Test
  def testAccessViaChainedProxy_forHttps_bypassChainedProxy_withSSLSupport {
    proxy = ProxyServer(createProxyConfig(chainedPort = Some(8081), isClientSSLEnable = true, chainProxyManager = gfwChainProxyManager))
    proxy start
    val proxyContent = request("https://developer.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    Assert.assertTrue(proxyContent.toString.length > 0)
  }


  @Test
  def testAccessViaUnavailableChainedProxy {
    proxy = ProxyServer(createProxyConfig(chainedPort = Some(8081)))
    chainProxy = ProxyServer(createProxyConfig(bindPort = 8082))

    chainProxy.start
    proxy.start
    try {
      request("http://apple.com/").socketTimeout(5 * 1000).viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    } catch {
      case _: Throwable => Assert.assertTrue(true)
    }

  }


  @Test
  def testAccessViaChainedProxyForHttps {
    proxy = ProxyServer(createProxyConfig(chainedPort = Some(8081)))
    chainProxy = ProxyServer(createProxyConfig(bindPort = 8081, isLocalProxy = false))

    chainProxy start

    proxy start
    val proxyContent = request("https://developer.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    Assert.assertTrue(proxyContent.toString.length > 0)

  }


  @Test
  def testAccessViaChainedProxy_withSSLSupport {
    proxy = ProxyServer(createProxyConfig(chainedPort = Some(8081), isClientSSLEnable = true))
    chainProxy = ProxyServer(createProxyConfig(bindPort = 8081, isLocalProxy = false, isServerSSLEnable = true))

    chainProxy start

    proxy start

    Assert.assertTrue(request("http://apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent.toString.length > 0)
  }

  @Test
  def testAccessViaChainedProxyForHttps_withSSLSupport {

    proxy = ProxyServer(createProxyConfig(chainedPort = Some(8081), isClientSSLEnable = true))
    chainProxy = ProxyServer(createProxyConfig(bindPort = 8081, isLocalProxy = false, isServerSSLEnable = true))

    chainProxy start

    proxy start

    Assert.assertTrue(request("https://developer.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent.toString.length > 0)

  }


  @Test
  def testAccessViaChainedProxy_withSSLSupport_forProgrammaticCertification {

    val config =
      """
        |port = 8080
        |chain-proxy{
        |    host ="localhost:8081"
        |}
        |proxy-server{
        |    ssl {
        |            enabled = false
        |    }
        |}
        |proxy-server-to-remote{
        |    ssl {
        |            enabled = true
        |    }
        |}
      """.stripMargin

    val chainedConfig =
      """
        |port = 8081
        |local=false
        |chain-proxy{
        |    host =""
        |}
        |proxy-server{
        |    ssl {
        |            enabled = true
        |    }
        |}
        |proxy-server-to-remote{
        |    ssl {
        |            enabled = false
        |    }
        |}
      """.stripMargin


    proxy = ProxyServer(new ProgrammaticCertificationProxyConfig(Some(ConfigFactory.load(ConfigFactory.parseString(config)))))
    chainProxy = ProxyServer(new ProgrammaticCertificationProxyConfig(Some(ConfigFactory.load(ConfigFactory.parseString(chainedConfig)))))

    chainProxy start

    proxy start

    Assert.assertTrue(request("http://apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent.toString.length > 0)

    Assert.assertTrue(request("https://developer.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent.toString.length > 0)
  }

}


class ChainedProxyManagerTest {
  Security.addProvider(new BouncyCastleProvider)

  @Test
  def testChainedProxyManager {
    val config =
      """
        |chain-proxy{
        |    host ="localhost:8081"
        |}
      """.stripMargin

    val request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "http://facebook.com")
    val host = new GFWChainProxyManager().getConnectHost(request)(new ProgrammaticCertificationProxyConfig(Some(ConfigFactory.load(ConfigFactory.parseString(config)))))
    Assert.assertFalse(host == new InetSocketAddress("localhost", 8081))
  }

  @Test
  def testPerformanceGFW {

    val request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "http://twitter.com")
    val manager = new GFWChainProxyManager()
    val now = System.currentTimeMillis()
    val host = manager.getConnectHost(request)(new SimpleProxyConfig)
    manager.getConnectHost(request)(new SimpleProxyConfig)
    manager.getConnectHost(request)(new SimpleProxyConfig)
    manager.getConnectHost(request)(new SimpleProxyConfig)
    manager.getConnectHost(request)(new SimpleProxyConfig)
    manager.getConnectHost(request)(new SimpleProxyConfig)
    manager.getConnectHost(request)(new SimpleProxyConfig)
    manager.getConnectHost(request)(new SimpleProxyConfig)
    manager.getConnectHost(request)(new SimpleProxyConfig)
    manager.getConnectHost(request)(new SimpleProxyConfig)
    println("################################" + (System.currentTimeMillis() - now))
    Assert.assertFalse(host == new InetSocketAddress("localhost", 8081))
  }

}