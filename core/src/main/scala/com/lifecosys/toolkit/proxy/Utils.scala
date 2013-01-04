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
import java.net.InetSocketAddress
import java.util.regex.Pattern
import org.jboss.netty.channel.{ChannelFutureListener, Channel}
import org.jboss.netty.buffer.ChannelBuffers
import com.lifecosys.toolkit.Logger

/**
 *
 *
 * @author Young Gu 
 * @version 1.0 12/19/12 4:57 PM
 */
object Utils {
  val logger = Logger(getClass)
  val httpPattern = Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE)
  val hostPortPattern = """([^:]*)(:?)(\d{0,5})""".r
  val connectProxyResponse: String = "HTTP/1.1 200 Connection established\r\n\r\n"


  def extractHostAndPort(uri: String) = {
    val noHttpUri = if (httpPattern.matcher(uri).matches())
      uri.substring(uri.indexOf("://") + 3)
    else
      uri

    val slashIndex = noHttpUri.indexOf("/")
    val hostPort = if (slashIndex == -1)
      noHttpUri
    else
      noHttpUri.substring(0, slashIndex)

    val hostPortPattern(host, colon, port) = hostPort

    (host, Some(port).filter(_.trim.length > 0).getOrElse("80"))
  }

  def extractHost(uri: String) = {
    val hostPort = extractHostAndPort(uri)
    new InetSocketAddress(hostPort._1, hostPort._2.toInt)
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
    val digits = "0123456789abcdef"
    var result = ""
    var index = 0
    while (index != data.length) {
      val value: Int = data(index) & 0xff
      result += digits.charAt(value >> 4)
      result += digits.charAt(value & 0xf)
      index = index + 1
    }
    result
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
