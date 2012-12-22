package com.lifecosys.toolkit.functional

import org.junit.{Assert, Test}
import java.security._
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.spec.{RSAPrivateCrtKeySpec, RSAPublicKeySpec}
import java.math.BigInteger
import java.net.InetSocketAddress
import com.lifecosys.toolkit.proxy.Utils

/**
 *
 *
 * @author Young Gu 
 * @version 1.0 12/19/12 4:58 PM
 */

class UtilsTest {

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
    var host = Utils.parseHostAndPort("http://127.0.0.1:8990/")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 8990), host)

    host = Utils.parseHostAndPort("https://127.0.0.1:8990/")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 8990), host)

    host = Utils.parseHostAndPort("127.0.0.1:8990/")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 8990), host)

    host = Utils.parseHostAndPort("127.0.0.1:8990")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 8990), host)

    host = Utils.parseHostAndPort("127.0.0.1")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 80), host)

    host = Utils.parseHostAndPort("127.0.0.1/test/sss/tyty/8989")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 80), host)

    host = Utils.parseHostAndPort("127.0.0.1/test/sss/tyty/89:89")
    Assert.assertEquals(new InetSocketAddress("127.0.0.1", 80), host)

    host = Utils.parseHostAndPort("http://download-ln.jetbrains.com/idea/ideaIC-12.0.1.tar.gz")
    Assert.assertEquals(new InetSocketAddress("download-ln.jetbrains.com", 80), host)

    try {
      Utils.parseHostAndPort("127.0.0.1:899000")
    } catch {
      case e: IllegalArgumentException => Assert.assertEquals("port out of range:899000", e.getMessage)
    }
  }
}
