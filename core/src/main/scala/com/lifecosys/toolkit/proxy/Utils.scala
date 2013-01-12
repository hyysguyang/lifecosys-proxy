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

package com.lifecosys.toolkit.proxy

import java.security.spec.{RSAPrivateCrtKeySpec, RSAPublicKeySpec}
import java.security.{KeyPairGenerator, Security, KeyFactory}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import scala.Some
import java.net.{URL, InetSocketAddress}
import java.util.regex.Pattern
import org.jboss.netty.channel.{ChannelFutureListener, Channel}
import org.jboss.netty.buffer.ChannelBuffers
import org.bouncycastle.util.encoders.Hex
import java.nio.charset.Charset
import org.jasypt.encryption.pbe.StandardPBEByteEncryptor
import java.util.zip.{Inflater, Deflater}

/**
 *
 *
 * @author Young Gu 
 * @version 1.0 12/19/12 4:57 PM
 */
object Utils {
  val UTF8: Charset = Charset.forName("UTF-8")
  val httpPattern = Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE)
  val hostPortPattern = """([^:]*)(:?)(\d{0,5})""".r
  val connectProxyResponse: String = "HTTP/1.1 200 Connection established\r\n\r\n"
  val deflater = new Deflater
  val inflater = new Inflater

  lazy val cryptor = {
    val standardEncryptor = new StandardPBEByteEncryptor
    standardEncryptor.setProviderName("BC")
    standardEncryptor.setAlgorithm("PBEWithSHAAnd3KeyTripleDES")
    standardEncryptor.setPassword( """nFJ@54GiretJGEg32%##43bdfw v345&78(&!~_r5w5 b^%%^875345@$$#@@$24!@#(@$$@%$@ VCDN{}Po}}PV D[GEJ G_""")
    standardEncryptor
  }

  /**
   * Just to avoid the security exception since we need strong encryption.
   */
  def installJCEPolicy {
    val field = Class.forName("javax.crypto.JceSecurity").getDeclaredField("isRestricted")
    field.setAccessible(true)
    field.set(null, java.lang.Boolean.FALSE)
  }


  def extractHostAndPort(uri: String) = {

    val url = if (uri.startsWith("http"))
      new URL(uri)
    else
      new URL("http://" + uri)
    (url.getHost, Some(url.getPort).filter(_ > 0).getOrElse(80))
  }

  def extractHost(uri: String) = {
    val hostPort = extractHostAndPort(uri)
    new InetSocketAddress(hostPort._1, hostPort._2)
  }

  def stripHost(uri: String): String = {

    if (!httpPattern.matcher(uri).matches())
      uri
    else {
      val noHttpUri: String = uri.substring(uri.indexOf("://") + 3)
      val slashIndex = noHttpUri.indexOf("/")
      if (slashIndex == -1) "/"
      else noHttpUri.substring(slashIndex)
    }
  }

  def closeChannel(channel: Channel) {
    logger.debug("Closing channel: %s".format(channel))
    if (channel.isConnected) channel.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
  }

  def toHex(data: Array[Byte]): String = {
    new String(Hex.encode(data), UTF8)
  }

  def generateGFWHostList = {
    val list = scala.io.Source.fromInputStream(getClass.getResourceAsStream("/gfwlist.txt")).getLines().filterNot {
      line => line.startsWith("!") || line.startsWith("@@") || line.startsWith("/")
    }.toList

    val result = list.map {
      case line if (line.startsWith("||")) => Utils.extractHostAndPort(line.substring(2))._1
      case line if (line.startsWith("|") || line.startsWith(".")) => Utils.extractHostAndPort(line.substring(1))._1
      case line if (line.indexOf('*') > 0) => Utils.extractHostAndPort(line)._1
    }

    result.foreach(println _)


    //    list.filter(_.startsWith("||")).foreach(println _)
    //    list.filterNot(_.startsWith("||")).filter(_.startsWith("|")).foreach(println _)

    //    val request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "http://docs.google.com")
    //    val host = new GFWChainProxyManager().getConnectHost(request)(new SimpleProxyConfig)
    //    println(host)
  }


  def main(args: Array[String]) {
    Security.addProvider(new BouncyCastleProvider());
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC");
    keyPairGenerator.initialize(2048);
    val keyPair = keyPairGenerator.generateKeyPair();


    val keyFac = KeyFactory.getInstance("RSA", "BC");
    val publicKeySpec = keyFac.getKeySpec(keyPair.getPublic(), classOf[RSAPublicKeySpec]);
    val privateCrtKeySpec = keyFac.getKeySpec(keyPair.getPrivate(), classOf[RSAPrivateCrtKeySpec]);
    println("==================Server KeyPair========================================")
    println("=========================Public========================================")
    println(toHex(privateCrtKeySpec.getModulus().toByteArray()))
    println(toHex(privateCrtKeySpec.getPublicExponent().toByteArray()))

    //
    //    System.out.println("==================Server KeyPair========================================");
    //    System.out.println("=========================Public========================================");
    //    System.out.println(toHex(publicKeySpec.getModulus().toByteArray()));
    //    System.out.println(toHex(publicKeySpec.getPublicExponent().toByteArray()));
    //    System.out.println("=========================Private========================================");
    //    System.out.println(toHex(privateCrtKeySpec.getModulus().toByteArray()));
    //    System.out.println(toHex(privateCrtKeySpec.getPublicExponent().toByteArray()));
    //    System.out.println(toHex(privateCrtKeySpec.getPrivateExponent().toByteArray()));
    //    System.out.println(toHex(privateCrtKeySpec.getPrimeP().toByteArray()));
    //    System.out.println(toHex(privateCrtKeySpec.getPrimeQ().toByteArray()));
    //    System.out.println(toHex(privateCrtKeySpec.getPrimeExponentP().toByteArray()));
    //    System.out.println(toHex(privateCrtKeySpec.getPrimeExponentQ().toByteArray()));
    //    System.out.println(toHex(privateCrtKeySpec.getCrtCoefficient().toByteArray()));
    //
    //    System.out.println("==================Server KeyPair========================================");
    //    System.out.println("=========================Public========================================");
    //    System.out.println(toHex(publicKeySpec.getModulus().toByteArray()));
    //    System.out.println(toHex(publicKeySpec.getPublicExponent().toByteArray()));
    //    System.out.println("=========================Private========================================");
    //    System.out.println(toHex(privateCrtKeySpec.getModulus().toByteArray()));
    //    System.out.println(toHex(privateCrtKeySpec.getPublicExponent().toByteArray()));
    //    System.out.println(toHex(privateCrtKeySpec.getPrivateExponent().toByteArray()));
    //    System.out.println(toHex(privateCrtKeySpec.getPrimeP().toByteArray()));
    //    System.out.println(toHex(privateCrtKeySpec.getPrimeQ().toByteArray()));
    //    System.out.println(toHex(privateCrtKeySpec.getPrimeExponentP().toByteArray()));
    //    System.out.println(toHex(privateCrtKeySpec.getPrimeExponentQ().toByteArray()));
    //    System.out.println(toHex(privateCrtKeySpec.getCrtCoefficient().toByteArray()));
    //
    //

  }


}
