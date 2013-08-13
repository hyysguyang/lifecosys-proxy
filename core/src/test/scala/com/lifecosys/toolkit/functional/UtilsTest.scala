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

import org.junit.Assert
import java.security.spec.RSAPrivateCrtKeySpec
import java.math.BigInteger
import java.net.InetSocketAddress

import java.security._
import org.bouncycastle.jce.provider.BouncyCastleProvider
import com.lifecosys.toolkit.proxy.Utils
import org.scalatest.{ BeforeAndAfterAll, FeatureSpec }
import org.apache.commons.io.IOUtils
import java.security.spec.RSAPublicKeySpec

/**
 *
 *
 * @author Young Gu
 * @version 1.0 12/19/12 4:58 PM
 */

class UtilsTest extends FeatureSpec with BeforeAndAfterAll {

  override protected def beforeAll() {
    Security.insertProviderAt(new BouncyCastleProvider, 1)
  }

  feature("Compress data") {

    scenario(" should compress/decompress empty data") {
      val decompressData = Utils.inflate(Utils.deflate(Array[Byte]()))
      assert(Array[Byte]() === decompressData)

      //      assert(Array[Byte]() === Utils.inflate(Array[Byte]()))
      //      assert(Array[Byte]() === Utils.deflate(Array[Byte](), Deflater.BEST_COMPRESSION))
    }

    scenario(" should decompress empty data") {
      assert(Array[Byte]() === Utils.inflate(Array[Byte]()))
    }

    scenario(" should compress/decompress data") {
      val data = IOUtils.toString(getClass.getResourceAsStream("/com/lifecosys/toolkit/functional/test_deflate_data.html"))
      val compressData = Utils.deflate(data.getBytes(Utils.UTF8))
      val decompressData = Utils.inflate(compressData)
      assert(data.getBytes().length > compressData.length)
      assert(data === new String(decompressData))
    }

  }

  //  @Test
  //  def investigation {
  //    val httpClient = HttpClients.custom()
  //      .setSSLSocketFactory(new SSLSocketFactory(Utils.trustAllSSLContext))
  //      .setProxy(new HttpHost("localhost", 8080))
  //      .build()
  //
  //    //    val client = new HttpPost("http://localhost:8080/proxy")
  //    //    client.setEntity(new ByteArrayEntity("This is just a test".getBytes(),ContentType.APPLICATION_OCTET_STREAM))
  //    //    Request.Put("http://localhost:8080/proxy").bodyByteArray("This is just a test".getBytes()).execute.returnContent.toString
  //    //    Assert.assertTrue(new GFWListJava().isBlockedByGFW("http://facebook.com"))
  //
  //    val string = IOUtils.toString(httpClient.execute(new HttpGet("https://developer.apple.com/")).getEntity.getContent)
  //    //    val string = IOUtils.toString(httpClient.execute(new HttpGet("http://stackoverflow.com/questions/5206010/using-apache-httpclient-for-https")).getEntity.getContent)
  //    //    println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$")
  //    println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$")
  //    println(string)
  //    println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$")
  //    //    println("$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$$")
  //
  //  }

  feature("Generate Hex KeySpec") {
    scenario(" should generate correct hex key") {
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
        new BigInteger(Utils.toHex(privateKeySpec.getCrtCoefficient.toByteArray), 16))
      assert(keyPair.getPublic === keyFactory.generatePublic(actualPublicKeySpec))
      assert(keyPair.getPrivate === keyFactory.generatePrivate(actualPrivateSpec))
    }
  }

  feature("Parse host") {
    scenario(" should parse all url") {
      var host = Utils.extractHost("http://127.0.0.1:8990/")
      assert(new InetSocketAddress("127.0.0.1", 8990) == host)

      host = Utils.extractHost("https://127.0.0.1:8990/")
      assert(new InetSocketAddress("127.0.0.1", 8990) == host)

      host = Utils.extractHost("127.0.0.1:8990/")
      assert(new InetSocketAddress("127.0.0.1", 8990) == host)

      host = Utils.extractHost("127.0.0.1:8990")
      assert(new InetSocketAddress("127.0.0.1", 8990) == host)

      host = Utils.extractHost("127.0.0.1")
      assert(new InetSocketAddress("127.0.0.1", 80) == host)

      host = Utils.extractHost("127.0.0.1/test/sss/tyty/8989")
      assert(new InetSocketAddress("127.0.0.1", 80) == host)

      host = Utils.extractHost("127.0.0.1/test/sss/tyty/89:89")
      assert(new InetSocketAddress("127.0.0.1", 80) == host)

      host = Utils.extractHost("127.0.0.1//test/hello/index.html")
      assert(new InetSocketAddress("127.0.0.1", 80) == host)

      host = Utils.extractHost("http://download-ln.jetbrains.com/idea/ideaIC-12.0.1.tar.gz")
      assert(new InetSocketAddress("download-ln.jetbrains.com", 80) == host)

      try {
        Utils.extractHost("127.0.0.1:899000")
      } catch {
        case e: Exception ⇒ Assert.assertTrue(true)
      }
    }
  }

}
