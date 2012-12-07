package com.lifecosys.toolkit.functional

import org.scalatest.{BeforeAndAfter, FunSuite}
import org.apache.http.client.fluent.Request
import com.lifecosys.toolkit.proxy._
import org.apache.http.HttpHost
import org.apache.commons.lang.StringUtils
import org.scalatest.junit.ShouldMatchersForJUnit
import java.net.InetSocketAddress
import org.apache.http.client.HttpResponseException
import org.jboss.netty.channel.ChannelException

/**
 * Created with IntelliJ IDEA.
 * User: young
 * Date: 12/2/12
 * Time: 12:56 AM
 * To change this template use File | Settings | File Templates.
 */
class ProxyTest extends FunSuite with ShouldMatchersForJUnit with BeforeAndAfter {
  var proxy: Proxy = null
  var chainProxy: Proxy = null
  before {
    proxy = Proxy(8080)
    chainProxy = Proxy(8081)

  }

  after {
    proxy shutdown

    if (chainProxy != null && chainProxy != None) {
      chainProxy shutdown
    }

    proxy = null
    chainProxy = null

  }

  test("Simple http client") {
    proxy.start
    //    Executor.registerScheme(new Scheme("https", 443, new SSLSocketFactory(SecureChatSslContextFactory.getClientContext)))
    //    println(Request.Get("https://developer.apple.com/").execute.returnContent)
    //    var proxyContent = Request.Get("https://developer.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    println(Request.Get("http://www.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent)


    //
    //    val proxy = new Proxy(8080)
    //    proxy start
    //    val proxyContent = Request.Get("http://www.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    //    println(proxyContent)
    //    proxy shutdown
  }


  test("Proxy server should can be shutdown") {
    proxy.start
    proxy.shutdown

    proxy = Proxy(8080)
    proxy.start

    intercept[ChannelException] {
      Proxy(8080).start
    }
  }


  test("Access http://www.apple.com/ should return the apple index page") {
    proxy.start
    val content = Request.Get("http://www.apple.com/").execute.returnContent
    println(content)
    val proxyContent = Request.Get("http://www.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    zip(proxyContent) should be(zip(content))
  }


  test("Access http://store.apple.com/ should return the apple index page") {
    proxy.start
    val content = Request.Get("http://store.apple.com/").execute.returnContent
    val proxyContent = Request.Get("http://store.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    zip(proxyContent) should be(zip(content))
  }


  test("Proxy can access internet via chained proxy") {
    proxy.chainProxies.+=(new InetSocketAddress(8081))
    chainProxy.start
    proxy.start

    val content = Request.Get("http://apple.com/").execute.returnContent
    var proxyContent = Request.Get("http://apple.com/").viaProxy(new HttpHost("localhost", 8081)).execute.returnContent
    zip(proxyContent) should be(zip(content))

    proxyContent = Request.Get("http://apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    zip(proxyContent) should be(zip(content))
  }


  test("Proxy can not access internet via chained proxy which is unavailable") {
    proxy.chainProxies.+=(new InetSocketAddress(8083))
    chainProxy.start
    proxy.start
    intercept[HttpResponseException] {
      Request.Get("http://apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    }
  }


  def zip(proxyContent: Any): String = {
    StringUtils.deleteWhitespace(proxyContent.toString).replace("\n", "").replace("\r", "")
  }


  //      Request.Post("http://targethost/login")
  //              .bodyForm(Form.form().add("username", "vip").add("password", "secret").build())
  //              .execute().returnContent();


}

