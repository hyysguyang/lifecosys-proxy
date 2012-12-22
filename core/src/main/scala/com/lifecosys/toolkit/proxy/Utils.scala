package com.lifecosys.toolkit.proxy

import java.security.spec.{RSAPrivateCrtKeySpec, RSAPublicKeySpec}
import java.security.{KeyPairGenerator, Security, KeyFactory}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import scala.Some
import java.net.InetSocketAddress

/**
 *
 *
 * @author Young Gu 
 * @version 1.0 12/19/12 4:57 PM
 */
object Utils {

  def parseHostAndPort(uri: String) = {
    val hostPortPattern = """(.*//|^)([^/\:]+)\:*(\d*).*""".r

    //    val hostPortPattern = """(.+)\:*(\d*).*""".r
    //
    //    val hostPort=uri.stripPrefix("https*://") match {
    //      case u if u.indexOf('/')>0 => u.substring(0,u.indexOf('/'))
    //      case _ => _
    //    }
    //
    val hostPortPattern(prefix, host, port) = uri
    new InetSocketAddress(host, Some(port).filter(_.trim.length > 0).getOrElse("80").toInt)
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
