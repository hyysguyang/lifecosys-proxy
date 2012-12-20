package com.lifecosys.toolkit.functional

import org.junit.{Assert, Test}
import java.security._
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.spec.{RSAPrivateCrtKeySpec, RSAPublicKeySpec}
import java.math.BigInteger
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
}
