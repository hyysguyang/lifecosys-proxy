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

package com.lifecosys.toolkit.functional

import org.junit.{Assert, Test}
import java.security._
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.spec.{RSAPrivateCrtKeySpec, RSAPublicKeySpec}
import java.math.BigInteger
import java.net.InetSocketAddress
import com.lifecosys.toolkit.proxy.Utils
import org.apache.http.client.fluent.Request
import org.apache.http.HttpHost

/**
 *
 *
 * @author Young Gu 
 * @version 1.0 12/19/12 4:58 PM
 */

class UtilsTest {

  Security.addProvider(new BouncyCastleProvider)

  @Test
  def investigation {
    //    Assert.assertTrue(new GFWListJava().isBlockedByGFW("http://facebook.com"))
    val string: String = Request.Get("http://twitter.com/").viaProxy(new HttpHost("localhost", 8080)).execute.returnContent.toString
    println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$")
    println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$")
    println(string)
    println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$")
    println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$")
  }

  @Test
  def testGenerateHexKeySpec {
    Security.addProvider(new BouncyCastleProvider)

    val keyPairGenerator: KeyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC")
    keyPairGenerator.initialize(64)
    val keyPair: KeyPair = keyPairGenerator.generateKeyPair

    val keyFactory = KeyFactory.getInstance("RSA", "BC")
    val publicKeySpec = keyFactory.getKeySpec(keyPair.getPublic, classOf[RSAPublicKeySpec])
    val privateKeySpec = keyFactory.getKeySpec(keyPair.getPrivate, classOf[RSAPrivateCrtKeySpec])

    val actualPublicKeySpec = new RSAPublicKeySpec(new BigInteger(Utils.toHex(publicKeySpec.getModulus.toByteArray), 16), new BigInteger(Utils.toHex(publicKeySpec.getPublicExponent.toByteArray), 16))
    val actualPrivateSpec = new RSAPrivateCrtKeySpec(
      new BigInteger(Utils.toHex(privateKeySpec.getModulus.toByteArray), 16),
      new BigInteger(Utils.toHex(privateKeySpec.getPublicExponent.toByteArray), 16),
      new BigInteger(Utils.toHex(privateKeySpec.getPrivateExponent.toByteArray), 16),
      new BigInteger(Utils.toHex(privateKeySpec.getPrimeP.toByteArray), 16),
      new BigInteger(Utils.toHex(privateKeySpec.getPrimeQ.toByteArray), 16),
      new BigInteger(Utils.toHex(privateKeySpec.getPrimeExponentP.toByteArray), 16),
      new BigInteger(Utils.toHex(privateKeySpec.getPrimeExponentQ.toByteArray), 16),
      new BigInteger(Utils.toHex(privateKeySpec.getCrtCoefficient.toByteArray), 16)
    )
    Assert.assertTrue(keyPair.getPublic == keyFactory.generatePublic(actualPublicKeySpec))
    Assert.assertTrue(keyPair.getPrivate == keyFactory.generatePrivate(actualPrivateSpec))

  }


  @Test
  def testParseHost {
    var host = Utils.extractHost("http://127.0.0.1:8990/")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 8990), host)

    host = Utils.extractHost("https://127.0.0.1:8990/")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 8990), host)

    host = Utils.extractHost("127.0.0.1:8990/")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 8990), host)

    host = Utils.extractHost("127.0.0.1:8990")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 8990), host)

    host = Utils.extractHost("127.0.0.1")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 80), host)

    host = Utils.extractHost("127.0.0.1/test/sss/tyty/8989")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 80), host)

    host = Utils.extractHost("127.0.0.1/test/sss/tyty/89:89")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 80), host)

    host = Utils.extractHost("127.0.0.1//test/hello/index.html")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 80), host)

    host = Utils.extractHost("http://download-ln.jetbrains.com/idea/ideaIC-12.0.1.tar.gz")
    Assert.assertEquals(new InetSocketAddress("download-ln.jetbrains.com", 80), host)

    try {
      Utils.extractHost("127.0.0.1:899000")
    } catch {
      case e: Exception => Assert.assertTrue(true)
    }
  }
}
