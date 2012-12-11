/**
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
import com.lifecosys.toolkit.proxy._
import org.apache.http.HttpHost
import org.apache.commons.lang.StringUtils
import java.net.InetSocketAddress
import org.apache.http.conn.scheme.Scheme
import org.apache.http.conn.ssl.SSLSocketFactory
import ssl.SecureChatSslContextFactory
import org.junit.{Assert, Test, After, Before}
import org.jboss.netty.channel.ChannelException
import org.jboss.netty.logging.InternalLogLevel

/**
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 12/2/12 12:56 AM
 */
class ProxyTest {
  var proxy: ProxyServer.Proxy = null
  var chainProxy: ProxyServer.Proxy = null

  @Before
  def before() {
    proxy = ProxyServer(8080)
    chainProxy = ProxyServer(8081)

  }

  @After
  def after() {
    proxy shutdown

    if (chainProxy != null) {
      chainProxy shutdown
    }

    proxy = null
    chainProxy = null

  }


  @Test(expected = classOf[ChannelException])
  def testShutdown {
    proxy.start
    proxy.shutdown

    proxy = ProxyServer(8080)
    proxy.start

    ProxyServer(8080).start

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
    val proxyContent = Request.Get("http://www.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    Assert.assertTrue(proxyContent.toString.length > 0)
  }

  @Test
  def testAnotherSimplePage {
    proxy start
    val proxyContent = Request.Get("http://store.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    Assert.assertTrue(proxyContent.toString.length > 0)
  }

  @Test
  def testAccessViaChainedProxy {

    proxy.chainProxies += new InetSocketAddress(8081)

    chainProxy start

    proxy start

    var proxyContent = Request.Get("http://apple.com/").viaProxy(new HttpHost("localhost", 8081)).execute.returnContent
    Assert.assertTrue(proxyContent.toString.length > 0)

    proxyContent = Request.Get("http://apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    Assert.assertTrue(proxyContent.toString.length > 0)

  }

  @Test
  def testAccessViaUnavailableChainedProxy {

    proxy.chainProxies.+=(new InetSocketAddress(8083))
    chainProxy.start
    proxy.start
    try {
      Request.Get("http://apple.com/").socketTimeout(5 * 1000).viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    } catch {
      case _: Throwable => Assert.assertTrue(true)
    }

  }

  @Test
  def testAccessHttps {
    proxy.start

    Executor.registerScheme(new Scheme("https", 443, new SSLSocketFactory(SecureChatSslContextFactory.getClientContext)))
    val content = Request.Get("https://developer.apple.com/").execute.returnContent
    Assert.assertTrue(content.toString.length > 0)

    var proxyContent = Request.Get("https://developer.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent

    Assert.assertTrue(proxyContent.toString.length > 0)

  }


  @Test
  def testAccessViaChainedProxyForHttps {

    proxy.chainProxies += new InetSocketAddress(8081)

    chainProxy start

    proxy start

    ProxyServer.isDebugged = InternalLogLevel.DEBUG;

    Executor.registerScheme(new Scheme("https", 443, new SSLSocketFactory(SecureChatSslContextFactory.getClientContext)))

    var proxyContent = Request.Get("https://developer.apple.com/").viaProxy(new HttpHost("localhost", 8081)).execute.returnContent
    Assert.assertTrue(proxyContent.toString.length > 0)

    proxyContent = Request.Get("https://developer.apple.com/").socketTimeout(5 * 1000).viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    Assert.assertTrue(proxyContent.toString.length > 0)

  }


  @Test
  def testAccessViaChainedProxy_withSSLSupport {
    after()
    proxy = ProxyServer(8080, false, true)
    chainProxy = ProxyServer(8081, true, false)
    proxy.chainProxies += new InetSocketAddress(8081)

    chainProxy start

    proxy start

    ProxyServer.isDebugged = InternalLogLevel.DEBUG;

    Executor.registerScheme(new Scheme("https", 443, new SSLSocketFactory(SecureChatSslContextFactory.getClientContext)))

    var proxyContent = Request.Get("http://apple.com/").socketTimeout(5 * 1000).viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    Assert.assertTrue(proxyContent.toString.length > 0)

  }

  @Test
  def testAccessViaChainedProxyForHttps_withSSLSupport {
    after()
    proxy = ProxyServer(8080, false, true)
    chainProxy = ProxyServer(8081, true, false)
    proxy.chainProxies += new InetSocketAddress(8081)

    chainProxy start

    proxy start

    ProxyServer.isDebugged = InternalLogLevel.DEBUG;

    Executor.registerScheme(new Scheme("https", 443, new SSLSocketFactory(SecureChatSslContextFactory.getClientContext)))

    var proxyContent = Request.Get("https://developer.apple.com/").socketTimeout(5000 * 1000).viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    Assert.assertTrue(proxyContent.toString.length > 0)


  }


  def zip(proxyContent: Any): String = {
    StringUtils.deleteWhitespace(proxyContent.toString).replace("\n", "").replace("\r", "")
  }

}

