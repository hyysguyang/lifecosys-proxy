package com.lifecosys.toolkit.ssl

/**
 *
 *
 * @author Young Gu
 * @version 1.0 7/25/13 12:55 PM
 */

import java.security.{ MessageDigest, Key }
import javax.crypto.{ CipherInputStream, Cipher }
import javax.crypto.spec.{ IvParameterSpec, SecretKeySpec }
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.InputStream
import org.jasypt.encryption.pbe.StandardPBEByteEncryptor

trait Encryptor {
  def encrypt(plainData: Array[Byte]): Array[Byte]
  def decrypt(encryptedData: Array[Byte]): Array[Byte]

}

class DefaultEncryptor extends Encryptor {

  val encryptor = createEncryptor

  def createEncryptor = {
    val standardEncryptor = new StandardPBEByteEncryptor
    standardEncryptor.setProviderName("BC")
    standardEncryptor.setAlgorithm("PBEWithSHAAnd3KeyTripleDES")
    standardEncryptor.setPassword("""nFJ@54GiretJGEg32%##43bdfw v345&78(&!~_r5w5 b^%%^875345@$$#@@$24!@#(@$$@%$@ VCDN{}Po}}PV D[GEJ G_""")
    standardEncryptor
  }

  def encrypt(plainData: Array[Byte]): Array[Byte] = synchronized(encryptor.encrypt(plainData))

  def decrypt(encryptedData: Array[Byte]): Array[Byte] = synchronized(encryptor.decrypt(encryptedData))
}
class JceEncryptor extends Encryptor {
  //Such as AES.
  val ALGORITHM = "AES"
  val HASH_ALGORITHM = "SHA-256"
  val provider = new BouncyCastleProvider
  val keySeed = Array[Byte](-51, -116, -59, 52, -44, -87, -94, 32, -88, -5, -128, -86, -65, -15, -110, -2, -72, 117, 17, -56, -63, 23, -66, 98, 108, 63, -61, -82, 92, 120, 7, 113, 73, 12, -111, 30, -112, 27, 16, -88, 42, 29, -15, 27, -100, 45, 41, -66, -20, -15, 73, 126, -20, 61, -71, 32, -106, -3, -40, 14, -114, -91, 49, 64)
  val encryptor = buildCipher(Cipher.ENCRYPT_MODE)
  val decryptor = buildCipher(Cipher.DECRYPT_MODE)

  private def buildCipher(mode: Int): Cipher = {
    val cipher = Cipher.getInstance(ALGORITHM, provider)
    cipher.init(mode, buildKey(), new IvParameterSpec(new Array[Byte](cipher.getBlockSize)))
    cipher
  }

  private def buildKey(): Key = {
    val digester = MessageDigest.getInstance(HASH_ALGORITHM, provider)
    digester.update(keySeed)
    new SecretKeySpec(digester.digest, ALGORITHM)
  }

  def encrypt(plainData: Array[Byte]): Array[Byte] = if (plainData.length == 0) plainData else synchronized(encryptor.doFinal(plainData))
  def decrypt(encryptedData: Array[Byte]): Array[Byte] = if (encryptedData.length == 0) encryptedData else synchronized(decryptor.doFinal(encryptedData))

  def encrypt(input: InputStream): InputStream = new CipherInputStream(input, encryptor)
  def decrypt(input: InputStream): InputStream = new CipherInputStream(input, decryptor)

}

