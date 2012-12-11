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

/**
 * Created with IntelliJ IDEA.
 * User: young
 * Date: 12/2/12
 * Time: 12:56 AM
 * To change this template use File | Settings | File Templates.
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
            case _:Throwable => Assert.assertTrue(true)
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
    }

    def zip(proxyContent: Any): String = {
        StringUtils.deleteWhitespace(proxyContent.toString).replace("\n", "").replace("\r", "")
    }

}

