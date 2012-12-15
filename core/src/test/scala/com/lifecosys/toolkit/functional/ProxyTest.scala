/*
 * ===Begin Copyright Notice===
 *
 * NOTICE
 *
 * THIS SOFTWARE IS THE PROPERTY OF AND CONTAINS CONFIDENTIAL INFORMATION OF
 * LIFECOSYS AND/OR ITS AFFILIATES OR SUBSIDIARIES AND SHALL NOT BE DISCLOSED
 * WITHOUT PRIOR WRITTEN PERMISSION. LICENSED CUSTOMERS MAY COPY AND ADAPT
 * THIS SOFTWARE FOR THEIR OWN USE IN ACCORDANCE WITH THE TERMS OF THEIR
 * SOFTWARE LICENSE AGREEMENT. ALL OTHER RIGHTS RESERVED.
 *
 * (c) COPYRIGHT 2013 LIFECOCYS. ALL RIGHTS RESERVED. THE WORD AND DESIGN
 * MARKS SET FORTH HEREIN ARE TRADEMARKS AND/OR REGISTERED TRADEMARKS OF
 * LIFECOSYS AND/OR ITS AFFILIATES AND SUBSIDIARIES. ALL RIGHTS RESERVED.
 * ALL LIFECOSYS TRADEMARKS LISTED HEREIN ARE THE PROPERTY OF THEIR RESPECTIVE
 * OWNERS.
 *
 * ===End Copyright Notice===
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
import com.lifecosys.toolkit.proxy.{ProxyServer, SimpleProxyConfig}

/**
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 12/2/12 12:56 AM
 */

object ProxyTestUtils {

  Executor.registerScheme(new Scheme("https", 443, new SSLSocketFactory(createStubSSLClientContext)))

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


  def createProxyConfig(bindPort: Int = 8080, chainedPort: Option[Int] = None, isServerSSLEnable: Boolean = false, isClientSSLEnable: Boolean = false) = {
    new SimpleProxyConfig {
      override val port = bindPort

      override val chainProxies = {
        chainedPort match {
          case Some(port) => mutable.MutableList[InetSocketAddress](new InetSocketAddress(port))
          case None => mutable.MutableList[InetSocketAddress]()
        }
      }

      override val serverSSLEnable = isServerSSLEnable
      override val proxyToServerSSLEnable = isClientSSLEnable
    }
  }





}

class SimpleProxyTest {
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  var proxy: ProxyServer.Proxy = null

  @Before
  def before() {
    proxy =  new ProxyServer.Proxy(createProxyConfig())
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

    proxy = new ProxyServer.Proxy(createProxyConfig())
    proxy.start

    new ProxyServer.Proxy(createProxyConfig()).start

  }

  @Test
  def testParseHost {
    var host = ProxyServer.parseHostAndPort("http://127.0.0.1:8990/")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 8990), host)

    host = ProxyServer.parseHostAndPort("https://127.0.0.1:8990/")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 8990), host)

    host = ProxyServer.parseHostAndPort("127.0.0.1:8990/")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 8990), host)

    host = ProxyServer.parseHostAndPort("127.0.0.1:8990")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 8990), host)

    host = ProxyServer.parseHostAndPort("127.0.0.1")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 80), host)

    host = ProxyServer.parseHostAndPort("127.0.0.1/test/sss/tyty/8989")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 80), host)

    host = ProxyServer.parseHostAndPort("127.0.0.1/test/sss/tyty/89:89")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 80), host)

    try {
      ProxyServer.parseHostAndPort("127.0.0.1:899000")
    } catch {
      case e: IllegalArgumentException => Assert.assertEquals("port out of range:899000", e.getMessage)
    }
  }

  @Test
  def testSimplePage {
    proxy start

    Assert.assertTrue(request("http://www.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent.toString.length > 0)
  }

  @Test
  def testAnotherSimplePage {
    proxy start

    Assert.assertTrue(request("http://store.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent.toString.length > 0)
  }


  @Test
  def testAccessHttps {
    proxy start

    Assert.assertTrue(request("https://developer.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent.toString.length > 0)
  }

}


class ChainedProxyTest {
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  var proxy: ProxyServer.Proxy = null
  var chainProxy: ProxyServer.Proxy = null

  @Before
  def before() {


  }

  @After
  def after() {
    proxy shutdown

    chainProxy shutdown

    proxy = null
    chainProxy = null

  }


  @Test
  def testAccessViaChainedProxy {
    proxy = new ProxyServer.Proxy(createProxyConfig(chainedPort = Some(8081)))
    chainProxy = new ProxyServer.Proxy(createProxyConfig(bindPort = 8081))

    chainProxy start

    proxy start

    var proxyContent = request("http://apple.com/").viaProxy(new HttpHost("localhost", 8081)).execute.returnContent
    Assert.assertTrue(proxyContent.toString.length > 0)

    proxyContent = request("http://apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    Assert.assertTrue(proxyContent.toString.length > 0)

  }


  @Test
  def testAccessViaUnavailableChainedProxy {
    proxy = new ProxyServer.Proxy(createProxyConfig(chainedPort = Some(8081)))
    chainProxy = new ProxyServer.Proxy(createProxyConfig(bindPort = 8081))

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
    proxy = new ProxyServer.Proxy(createProxyConfig(chainedPort = Some(8081)))
    chainProxy = new ProxyServer.Proxy(createProxyConfig(bindPort = 8081))

    chainProxy start

    proxy start

    var proxyContent = request("https://developer.apple.com/").viaProxy(new HttpHost("localhost", 8081)).execute.returnContent
    Assert.assertTrue(proxyContent.toString.length > 0)

    proxyContent = request("https://developer.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    Assert.assertTrue(proxyContent.toString.length > 0)

  }


  @Test
  def testAccessViaChainedProxy_withSSLSupport {
    proxy = new ProxyServer.Proxy(createProxyConfig(chainedPort = Some(8081), isClientSSLEnable = true))
    chainProxy = new ProxyServer.Proxy(createProxyConfig(bindPort = 8081, isServerSSLEnable = true))

    chainProxy start

    proxy start

    Assert.assertTrue(request("http://apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent.toString.length > 0)
  }

  @Test
  def testAccessViaChainedProxyForHttps_withSSLSupport {

    proxy = new ProxyServer.Proxy(createProxyConfig(chainedPort = Some(8081), isClientSSLEnable = true))
    chainProxy = new ProxyServer.Proxy(createProxyConfig(bindPort = 8081, isServerSSLEnable = true))

    chainProxy start

    proxy start

    Assert.assertTrue(request("https://developer.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent.toString.length > 0)

  }


}