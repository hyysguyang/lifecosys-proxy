package com.lifecosys.toolkit.functional

import org.scalatest.{ BeforeAndAfterAll, FeatureSpec }
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import com.lifecosys.toolkit.ssl.DefaultEncryptor

/**
 *
 *
 * @author Young Gu
 * @version 1.0 7/25/13 1:05 PM
 */
class EncryptorTest extends FeatureSpec with BeforeAndAfterAll {

  override protected def beforeAll() {
    Security.insertProviderAt(new BouncyCastleProvider, 1)
  }

  feature("Encryptor for binary data") {
    scenario(" should encrypt and decrypt string") {
      val data = "Hello, world."
      assert(data == new String(new DefaultEncryptor().decrypt(new DefaultEncryptor().encrypt(data.getBytes()))))
    }
  }
}
