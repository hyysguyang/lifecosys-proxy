package com.lifecosys.toolkit.proxy

import java.security.spec.{RSAPrivateCrtKeySpec, RSAPublicKeySpec}
import java.security.{KeyPairGenerator, Security, KeyFactory}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import scala.Some
import java.net.InetSocketAddress
import java.util.regex.Pattern

/**
 *
 *
 * @author Young Gu 
 * @version 1.0 12/19/12 4:57 PM
 */
object Utils {
  val httpPattern = Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE)
  val hostPortPattern = """([^:]*)(:?)(\d{0,5})""".r

  def parseHostAndPort(uri: String) = {
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
    new InetSocketAddress(host, Some(port).filter(_.trim.length > 0).getOrElse("80").toInt)

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
