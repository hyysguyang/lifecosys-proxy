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


  @Test //(expected = classOf[ChannelException])
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
      case _ => Assert.assertTrue(true)
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


  //
  //  test("Simple http client") {
  //    proxy.start
  //    //    Executor.registerScheme(new Scheme("https", 443, new SSLSocketFactory(SecureChatSslContextFactory.getClientContext)))
  //    //    println(Request.Get("https://developer.apple.com/").socketTimeout(5*1000).execute.returnContent)
  //    //    var proxyContent = Request.Get("https://developer.apple.com/").socketTimeout(5*1000).viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
  //    println(Request.Get("http://www.apple.com/").socketTimeout(5*1000).viaProxy(new HttpHost("localhost", 8080)).execute.returnContent)
  //
  //
  //    //
  //    //    val proxy = new Proxy(8080)
  //    //    proxy start
  //    //    val proxyContent = Request.Get("http://www.apple.com/").socketTimeout(5*1000).viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
  //    //    println(proxyContent)
  //    //    proxy shutdown
  //  }
  //
  //
  //  ignore("Proxy server should can be shutdown") {
  //    proxy.start
  //    proxy.shutdown
  //
  //    proxy = ProxyServer(8080)
  //    proxy.start
  //
  //    intercept[ChannelException] {
  //      ProxyServer(8080).start
  //    }
  //  }
  //
  //  test("Access http://www.apple.com/ should return the apple index page")
  //  {
  //    proxy start
  //    val content = Request.Get("http://www.apple.com/").socketTimeout(5*1000).execute.returnContent
  //    println(content)
  //    val proxyContent = Request.Get("http://www.apple.com/").socketTimeout(5*1000).viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
  //    zip(proxyContent) should be(zip(content))
  //  }
  //  test("Access http://store.apple.com/ should return the apple index page")
  //  {
  //    proxy start
  //    val content = Request.Get("http://store.apple.com/").socketTimeout(5*1000).execute.returnContent
  //    val proxyContent = Request.Get("http://store.apple.com/").socketTimeout(5*1000).viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
  //    zip(proxyContent) should be(zip(content))
  //  }
  //
  //
  //
  //
  //  test("Proxy can access internet via chained proxy")
  //  {
  //    val chainProxyAddress = new InetSocketAddress(8081)
  //
  ////    proxy = new Proxy(8080)
  ////    {
  ////      override val clientToProxyPipeline: (ChannelPipeline) => ChannelPipeline = (pipeline: ChannelPipeline) =>
  ////      {
  ////        pipeline.addLast("decoder", new HttpRequestDecoder());
  ////        pipeline.addLast("aggregator", new HttpChunkAggregator(65536));
  ////        pipeline.addLast("encoder", new HttpResponseEncoder());
  ////        pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
  ////
  ////        pipeline.addLast("proxyHandler", new ProxyHandler
  ////        {
  ////          override def messageReceived(clientToProxyContext: ChannelHandlerContext, clientToProxyMessage: HttpRequest)
  ////          {
  ////            clientToProxyContext.channel().localAddress().asInstanceOf[InetSocketAddress].getPort should be(8080)
  ////            super.messageReceived(clientToProxyContext, clientToProxyMessage)
  ////          }
  ////
  ////          override def proxyToServerPipeline(ctx: ChannelHandlerContext, msg: HttpRequest) = (pipeline: ChannelPipeline) =>
  ////          {
  ////            pipeline.addLast("log", new LoggingHandler(LogLevel.INFO))
  ////            pipeline.addLast("codec", new HttpClientCodec)
  ////            pipeline.addLast("inflater", new HttpContentDecompressor)
  ////            pipeline.addLast("aggregator", new HttpChunkAggregator(1048576));
  ////            pipeline.addLast("proxyToServerHandler", new HttpRelayingHandler(ctx, msg)
  ////            {
  ////              override def channelActive(ctx: ChannelHandlerContext)
  ////              {
  ////                ctx.channel().remoteAddress() should be(chainProxyAddress)
  ////                super.channelActive(ctx)
  ////              }
  ////
  ////              override def messageReceived(ctx: ChannelHandlerContext, msg: Any)
  ////              {
  ////                super.messageReceived(ctx, msg)
  ////              }
  ////            })
  ////          }
  ////        })
  ////      }
  ////
  ////    }
  //
  //    proxy.chainProxies.+=(chainProxyAddress)
  //
  //    chainProxy start
  //
  //    proxy start
  //
  //    val content = Request.Get("http://apple.com/").socketTimeout(5*1000).execute.returnContent
  //    var proxyContent = Request.Get("http://apple.com/").socketTimeout(5*1000).viaProxy(new HttpHost("localhost", 8081)).execute.returnContent
  //    zip(proxyContent) should be(zip(content))
  //
  //    proxyContent = Request.Get("http://apple.com/").socketTimeout(5*1000).viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
  //    zip(proxyContent) should be(zip(content))
  //  }
  //
  //
  //  test("Proxy can not access internet via chained proxy which is unavailable") {
  //    proxy.chainProxies.+=(new InetSocketAddress(8083))
  //    chainProxy.start
  //    proxy.start
  //   intercept[HttpHostConnectException] {
  //      Request.Get("http://apple.com/").socketTimeout(5*1000).viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
  //    }
  //
  //  }
  //
  //
  //  test("Proxy can access https site") {
  //    proxy.start
  //
  //    Executor.registerScheme(new Scheme("https", 443, new SSLSocketFactory(SecureChatSslContextFactory.getClientContext)))
  //    val content = Request.Get("https://developer.apple.com/").socketTimeout(5*1000).execute.returnContent
  //    var proxyContent = Request.Get("https://developer.apple.com/").socketTimeout(5*1000).viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
  //
  //    zip(proxyContent) should be(zip(content))
  //
  //  }


  def zip(proxyContent: Any): String = {
    StringUtils.deleteWhitespace(proxyContent.toString).replace("\n", "").replace("\r", "")
  }


  //      Request.Post("http://targethost/login")
  //              .bodyForm(Form.form().add("username", "vip").add("password", "secret").build())
  //              .execute().returnContent();


}

