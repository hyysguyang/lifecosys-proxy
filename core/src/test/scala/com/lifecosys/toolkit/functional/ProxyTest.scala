package com.lifecosys.toolkit.functional

import org.scalatest.{BeforeAndAfter, FunSuite, GivenWhenThen, FeatureSpec}
import collection.mutable.{MutableList, Stack}
import org.apache.http.client.fluent.{Form, Request}
import com.lifecosys.toolkit.proxy._
import org.apache.http.HttpHost
import org.apache.commons.lang.StringUtils
import org.scalatest.junit.ShouldMatchersForJUnit
import java.security.KeyStore
import java.io.{File, FileInputStream}
import java.net.InetSocketAddress
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http._
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.channel.{ChannelPipeline, ChannelHandlerContext}
import io.netty.handler.logging.{LogLevel, LoggingHandler}

/**
 * Created with IntelliJ IDEA.
 * User: young
 * Date: 12/2/12
 * Time: 12:56 AM
 * To change this template use File | Settings | File Templates.
 */
class ProxyTest extends FunSuite with ShouldMatchersForJUnit with BeforeAndAfter
{
  var proxy: Proxy = null
  var chainProxy: Proxy = null
  before
  {
    proxy = new Proxy(8080)
    chainProxy = new Proxy(8081)

  }

  after
  {
    proxy shutdown

    if (chainProxy != null)
    {
      chainProxy shutdown
    }

    proxy = null
    chainProxy = null

  }

  test("Simple http client")
  {
    chainProxy start

    proxy.chainProxies += new InetSocketAddress(8081)
    proxy start

    var proxyContent = Request.Get("http://www.apple.com").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    println(proxyContent.toString)

    proxy shutdown



    //
    //    val proxy = new Proxy(8080)
    //    proxy start
    //    val proxyContent = Request.Get("http://www.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    //    println(proxyContent)
    //    proxy shutdown
  }
  test("Access http://www.apple.com/ should return the apple index page")
  {
    proxy start
    val content = Request.Get("http://www.apple.com/").execute.returnContent
    println(content)
    val proxyContent = Request.Get("http://www.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    zip(proxyContent) should be(zip(content))
  }
  test("Access http://store.apple.com/ should return the apple index page")
  {
    proxy start
    val content = Request.Get("http://store.apple.com/").execute.returnContent
    val proxyContent = Request.Get("http://store.apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    zip(proxyContent) should be(zip(content))
  }




  test("Chain proxy: access http://apple.com/ should return the apple index page")
  {
    val chainProxyAddress = new InetSocketAddress(8081)

    proxy = new Proxy(8080)
    {
      override val clientToProxyPipeline: (ChannelPipeline) => ChannelPipeline = (pipeline: ChannelPipeline) =>
      {
        pipeline.addLast("decoder", new HttpRequestDecoder());
        pipeline.addLast("aggregator", new HttpChunkAggregator(65536));
        pipeline.addLast("encoder", new HttpResponseEncoder());
        pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());

        pipeline.addLast("proxyHandler", new ProxyHandler
        {
          override def messageReceived(clientToProxyContext: ChannelHandlerContext, clientToProxyMessage: HttpRequest)
          {
            clientToProxyContext.channel().localAddress().asInstanceOf[InetSocketAddress].getPort should be(8080)
            super.messageReceived(clientToProxyContext, clientToProxyMessage)
          }

          override def proxyToServerPipeline(ctx: ChannelHandlerContext, msg: HttpRequest) = (pipeline: ChannelPipeline) =>
          {
            pipeline.addLast("log", new LoggingHandler(LogLevel.INFO))
            pipeline.addLast("codec", new HttpClientCodec)
            pipeline.addLast("inflater", new HttpContentDecompressor)
            pipeline.addLast("aggregator", new HttpChunkAggregator(1048576));
            pipeline.addLast("proxyToServerHandler", new ProxyToServerHandler(ctx, msg)
            {
              override def channelActive(ctx: ChannelHandlerContext)
              {
                ctx.channel().remoteAddress() should be(chainProxyAddress)
                super.channelActive(ctx)
              }

              override def messageReceived(ctx: ChannelHandlerContext, msg: Any)
              {
                super.messageReceived(ctx, msg)
              }
            })
          }
        })
      }

    }

    proxy.chainProxies.+=(chainProxyAddress)

    chainProxy start

    proxy start

    val content = Request.Get("http://apple.com/").execute.returnContent
    var proxyContent = Request.Get("http://apple.com/").viaProxy(new HttpHost("localhost", 8081)).execute.returnContent
    zip(proxyContent) should be(zip(content))

    proxyContent = Request.Get("http://apple.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent
    zip(proxyContent) should be(zip(content))
  }


  def zip(proxyContent: Any): String =
  {
    StringUtils.deleteWhitespace(proxyContent.toString).replace("\n", "").replace("\r", "")
  }


  //      Request.Post("http://targethost/login")
  //              .bodyForm(Form.form().add("username", "vip").add("password", "secret").build())
  //              .execute().returnContent();


}

