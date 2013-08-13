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

package com.lifecosys.toolkit.proxy.server

import java.security._
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.spec.{ RSAPrivateCrtKeySpec, RSAPublicKeySpec }
import java.math.BigInteger
import java.net.{ Socket, InetSocketAddress }
import com.lifecosys.toolkit.proxy.Utils
import org.apache.http.HttpHost
import org.apache.http.impl.client.HttpClients
import org.apache.http.client.methods.HttpGet
import org.apache.commons.io.IOUtils
import org.apache.http.conn.ssl.SSLSocketFactory
import javax.net.ssl.{ X509TrustManager, SSLContext }
import java.security.cert.X509Certificate
import org.apache.http.client.config.RequestConfig
import org.apache.http.config.SocketConfig
import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.handler.codec.http.HttpRequestEncoder
import java.io.ByteArrayOutputStream
import com.typesafe.scalalogging.slf4j.Logging

/**
 *
 *
 * @author Young Gu
 * @version 1.0 12/19/12 4:58 PM
 */

object Main extends Logging {

  def main(args: Array[String]) {

    //    val socket = new Socket()
    //    socket.setKeepAlive(true)
    //    socket.setTcpNoDelay(true)
    //    socket.setSoTimeout(1000 * 1200)
    //    socket.connect(new InetSocketAddress("localhost", 8080))
    //
    //    val output = socket.getOutputStream
    //    val input = socket.getInputStream
    //    logger.error("#########################" + socket.isConnected)
    //    output.flush()
    //    //    logger.error(IOUtils.toString(IOUtils.toBufferedInputStream(input)))
    //
    //
    //    val request =
    //      "GET / HTTP/1.1\r\n" +
    //        "Host: localhost\r\n" +
    //        "User-Agent: whatever\r\n" +
    //        "Cookie: c1=stuff\r\n" +
    //        "\r\n"
    //
    //    output.write(request.getBytes)
    //    output.flush()
    //
    //    val buffer = new SessionInputBufferImpl(new HttpTransportMetricsImpl(), 1024, 2048, null, null)
    //    buffer.bind(new ChannelBufferInputStream(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer)))
    //    val parser = new DefaultHttpRequestParser(buffer)
    //    val proxyRequest = parser.parse()
    //
    //
    //    val stream: ByteArrayOutputStream = new ByteArrayOutputStream()
    //    def read = {
    //      val byte=input.read()
    //      println(byte)
    //      if (byte != -1) stream.write(byte)
    //      byte != -1
    //    }
    //
    //    while (read) {
    //
    //    }
    //
    //
    //
    //
    //
    //    logger.error(Utils.hexDumpToString(stream.toByteArray))

    System.setProperty("javax.net.debug", "all")
    val httpClient = HttpClients.custom()
      //      .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(2).setConnectionRequestTimeout(2).build())
      .setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(10000 * 1000).build())
      .setSSLSocketFactory(new SSLSocketFactory(Utils.trustAllSSLContext, SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER))
      .setProxy(new HttpHost("localhost", 8081))
      .build()

    //    val client = new HttpPost("http://localhost:8080/proxy")
    //    client.setEntity(new ByteArrayEntity("This is just a test".getBytes(),ContentType.APPLICATION_OCTET_STREAM))
    //    Request.Put("http://localhost:8080/proxy").bodyByteArray("This is just a test".getBytes()).execute.returnContent.toString
    //    Assert.assertTrue(new GFWListJava().isBlockedByGFW("http://facebook.com"))

    //
    //    DefaultHttpRequest(chunked: false)
    //    POST http://evintl-ocsp.verisign.com/ HTTP/1.1
    //    Host: evintl-ocsp.verisign.com
    //    User-Agent: Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:23.0) Gecko/20100101 Firefox/23.0
    //    Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8
    //Accept-Language: zh,en-us;q=0.7,en;q=0.3
    //Accept-Encoding: gzip, deflate
    //Content-Length: 115
    //Content-Type: application/ocsp-request
    //Connection: keep-alive
    val string = IOUtils.toString(httpClient.execute(new HttpGet("http://www.baidu.com/")).getEntity.getContent)
    //    val string = IOUtils.toString(httpClient.execute(new HttpGet("https://devimages.apple.com.edgekey.net/assets/scripts/ac_retina.js")).getEntity.getContent)
    //            val string = IOUtils.toString(httpClient.execute(new HttpGet("http://localhost:9080/test/index.html")).getEntity.getContent)
    //    val string = IOUtils.toString(httpClient.execute(new HttpGet("https://localhost:8443/test/index.html")).getEntity.getContent)
    //        val string = IOUtils.toString(httpClient.execute(new HttpGet("http://localhost:8080/index.html")).getEntity.getContent)
    //    val string = IOUtils.toString(httpClient.execute(new HttpGet("http://stackoverflow.com/questions/5206010/using-apache-httpclient-for-https")).getEntity.getContent)
    //    println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$")
    println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$")
    println(string)
    httpClient.close()
    println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$")
    //    println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$")

  }

}
