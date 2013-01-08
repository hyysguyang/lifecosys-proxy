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

import org.jboss.netty.logging.InternalLogLevel
import java.net.InetSocketAddress
import org.jboss.netty.channel.socket.{ClientSocketChannelFactory, ServerSocketChannelFactory}
import javax.net.ssl.{TrustManagerFactory, KeyManagerFactory, SSLContext}
import org.jboss.netty.channel.group.{DefaultChannelGroup, ChannelGroup}
import collection.mutable
import org.jboss.netty.channel.socket.nio.{NioClientSocketChannelFactory, NioServerSocketChannelFactory}
import java.util.concurrent.{SynchronousQueue, TimeUnit, ThreadPoolExecutor}
import com.lifecosys.toolkit.ssl.SSLManager
import java.io.InputStream
import java.security.{Security, KeyFactory, SecureRandom, KeyStore}
import com.typesafe.config.{ConfigFactory, Config}
import java.security.spec.{RSAPrivateCrtKeySpec, RSAPublicKeySpec}
import org.bouncycastle.x509.X509V3CertificateGenerator
import java.math.BigInteger
import org.bouncycastle.jce.X509Principal
import java.util.Date
import java.security.cert.Certificate

/**
 *
 *
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 12/19/12 10:16 PM
 */
trait ProxyConfig {

  val port: Int

  val serverSSLEnable: Boolean

  val proxyToServerSSLEnable: Boolean

  val loggerLevel: InternalLogLevel

  val isLocal: Boolean

  val chainProxies: scala.collection.mutable.MutableList[InetSocketAddress]

  val serverSocketChannelFactory: ServerSocketChannelFactory

  val clientSocketChannelFactory: ClientSocketChannelFactory

  val serverSSLContext: SSLContext

  val clientSSLContext: SSLContext

  val allChannels: ChannelGroup = new DefaultChannelGroup("HTTP-Proxy-Server")

  def getChainProxyManager: ChainProxyManager = new DefaultChainProxyManager
}


class SimpleProxyConfig extends ProxyConfig {

  override val port = 9050

  override val serverSSLEnable = false

  override val proxyToServerSSLEnable = false

  override val loggerLevel: InternalLogLevel = InternalLogLevel.INFO

  val isLocal: Boolean = true

  override val chainProxies = mutable.MutableList[InetSocketAddress]()


  override val serverSocketChannelFactory = new NioServerSocketChannelFactory(new ThreadPoolExecutor(10, 30, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable]), new ThreadPoolExecutor(10, 30, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable]))

  override val clientSocketChannelFactory = new NioClientSocketChannelFactory(new ThreadPoolExecutor(10, 30, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable]), new ThreadPoolExecutor(10, 30, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable]))

  val sslManger = new SSLManager {
    val keyStorePassword = "killccp"
    val keyManagerKeyStoreInputStream = classOf[SimpleProxyConfig].getResourceAsStream("/binary/keystore/lifecosys-proxy-server-keystore.jks")
    val trustKeyStorePassword = "killccp"
    val trustManagerKeyStoreInputStream = classOf[SimpleProxyConfig].getResourceAsStream("/binary/keystore/lifecosys-proxy-client-for-server-trust-keystore.jks")

    def getServerSSLContext: SSLContext = getSSLContext(classOf[SimpleProxyConfig].getResourceAsStream("/binary/keystore/lifecosys-proxy-server-keystore.jks"), "killccp", classOf[SimpleProxyConfig].getResourceAsStream("/binary/keystore/lifecosys-proxy-client-for-server-trust-keystore.jks"), "killccp")

    def getProxyToServerSSLContext: SSLContext = getSSLContext(classOf[SimpleProxyConfig].getResourceAsStream("/binary/keystore/lifecosys-proxy-client-keystore.jks"), "killccp", classOf[SimpleProxyConfig].getResourceAsStream("/binary/keystore/lifecosys-proxy-server-for-client-trust-keystore.jks"), "killccp")

    def getSSLContext(keyManagerKeyStoreInputStream: InputStream,
                      keyStorePassword: String,
                      trustManagerKeyStoreInputStream: InputStream,
                      trustKeyStorePassword: String,
                      keystoreType: String = "JKS",
                      algorithm: String = "SunX509"): SSLContext = {
      val keyStore: KeyStore = KeyStore.getInstance(keystoreType)
      keyStore.load(keyManagerKeyStoreInputStream, keyStorePassword.toCharArray)
      val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance(algorithm)
      keyManagerFactory.init(keyStore, keyStorePassword.toCharArray)
      val trustKeyStore: KeyStore = KeyStore.getInstance(keystoreType)
      trustKeyStore.load(trustManagerKeyStoreInputStream, trustKeyStorePassword.toCharArray)
      val trustManagerFactory: TrustManagerFactory = TrustManagerFactory.getInstance(algorithm)
      trustManagerFactory.init(trustKeyStore)
      val clientContext = SSLContext.getInstance("SSL")
      clientContext.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, new SecureRandom)
      clientContext
    }
  }

  override val serverSSLContext = sslManger.getServerSSLContext

  override val clientSSLContext = sslManger.getProxyToServerSSLContext

}


abstract class DefaultProxyConfig(config: Option[Config] = None) extends ProxyConfig {

  val thisConfig = config.getOrElse(ConfigFactory.load())

  val name = thisConfig.getString("name")

  val serverThreadCorePoolSize = thisConfig.getInt("proxy-server.thread.corePoolSize")
  val serverThreadMaximumPoolSize = thisConfig.getInt("proxy-server.thread.maximumPoolSize")
  val serverSSLKeystorePassword = thisConfig.getString("proxy-server.ssl.keystore-password")
  val serverSSLKeystorePath = thisConfig.getString("proxy-server.ssl.keystore-path")
  val serverSSLTrustKeystorePath = thisConfig.getString("proxy-server.ssl.trust-keystore-path")

  val proxyToServerThreadCorePoolSize = thisConfig.getInt("proxy-server-to-remote.thread.corePoolSize")
  val proxyToServerThreadMaximumPoolSize = thisConfig.getInt("proxy-server-to-remote.thread.maximumPoolSize")
  val proxyToServerSSLKeystorePassword = thisConfig.getString("proxy-server-to-remote.ssl.keystore-password")
  val proxyToServerSSLKeystorePath = thisConfig.getString("proxy-server-to-remote.ssl.keystore-path")
  val proxyToServerSSLTrustKeystorePath = thisConfig.getString("proxy-server-to-remote.ssl.trust-keystore-path")

  val serverExecutor = new ThreadPoolExecutor(serverThreadCorePoolSize, serverThreadMaximumPoolSize, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable])
  val clientExecutor = new ThreadPoolExecutor(proxyToServerThreadCorePoolSize, proxyToServerThreadMaximumPoolSize, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable])


  override val port = thisConfig.getInt("port")
  override val serverSSLEnable = thisConfig.getBoolean("proxy-server.ssl.enabled")
  override val proxyToServerSSLEnable = thisConfig.getBoolean("proxy-server-to-remote.ssl.enabled")

  override val loggerLevel = InternalLogLevel.valueOf(thisConfig.getString("logger-level"))

  override val serverSocketChannelFactory = new NioServerSocketChannelFactory(serverExecutor, serverExecutor)
  override val clientSocketChannelFactory = new NioClientSocketChannelFactory(clientExecutor, clientExecutor)
  override val isLocal = thisConfig.getBoolean("local")
  override val chainProxies = thisConfig.getString("chain-proxy.host") match {
    case host: String if host.trim.length > 0 => mutable.MutableList[InetSocketAddress](Utils.extractHost(thisConfig.getString("chain-proxy.host").replaceFirst(" ", ":")))
    case _ => mutable.MutableList[InetSocketAddress]()
  }


  def sslManger: SSLManager

  lazy override val serverSSLContext = sslManger.getServerSSLContext

  lazy override val clientSSLContext = sslManger.getProxyToServerSSLContext

}


class DefaultStaticCertificationProxyConfig(config: Option[Config] = None) extends DefaultProxyConfig(config) {
  override def sslManger = new SSLManager {
    def getServerSSLContext: SSLContext = {
      val keyStore: KeyStore = KeyStore.getInstance("JKS")
      keyStore.load(classOf[DefaultProxyConfig].getResourceAsStream(serverSSLKeystorePath), serverSSLKeystorePassword.toCharArray)
      val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
      keyManagerFactory.init(keyStore, serverSSLKeystorePassword.toCharArray)
      val clientContext = SSLContext.getInstance("SSL")
      clientContext.init(keyManagerFactory.getKeyManagers, null, new SecureRandom)
      clientContext
    }

    def getProxyToServerSSLContext: SSLContext = {
      val trustKeyStore: KeyStore = KeyStore.getInstance("JKS")
      trustKeyStore.load(classOf[DefaultProxyConfig].getResourceAsStream(proxyToServerSSLTrustKeystorePath), proxyToServerSSLKeystorePassword.toCharArray)
      val trustManagerFactory: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
      trustManagerFactory.init(trustKeyStore)
      val clientContext = SSLContext.getInstance("SSL")
      clientContext.init(null, trustManagerFactory.getTrustManagers, new SecureRandom)
      clientContext
    }
  }
}


class ProgrammaticCertificationProxyConfig(config: Option[Config] = None) extends DefaultProxyConfig(config) {
  val keyFactory = KeyFactory.getInstance("RSA", "BC")
  val protocol = "SSL"
  val keyStoreType = "BKS"
  val algorithm = "X509"

  val serverKeyStorePassword = "killccp"
  val serverAlias = "proxy-server-alias"
  val publicServerKeySpec = new RSAPublicKeySpec(
    new BigInteger("00c26504945f6c14e6e76675f843e2eb0918f30e41a13d49baa29464e185ecd9dfdf7b4a121c203f7852a5f201f44acec90000b32053e92e80e1a947d7f21e7c21e5cb3b7a4c5ca777c5f99df345d5874389d4763b265efc2ff3f2b123ac73641a16eb3b6fc5cd94eda099d78b483cbf3113ca9382ba36020309f8188434cfd3f3d0159c56fb089b46ea6808290a1c7db7fd6611c3bcc35e226b4e9d1ddacc1060cfea52550967b2545c7b7ffc330dbe91cafbf8bbbf04078e66c695904f9761bdc7c64912ab395292d86e7cf6302037089339b6d2c4e803350022797ad1c1d1623f1e3fe9539fc75acd714f65f72a08b161f361232e51ffec4f454c705476aeb9", 16),
    new BigInteger("010001", 16))


  val privateServerKeySpec = new RSAPrivateCrtKeySpec(
    new BigInteger("00c26504945f6c14e6e76675f843e2eb0918f30e41a13d49baa29464e185ecd9dfdf7b4a121c203f7852a5f201f44acec90000b32053e92e80e1a947d7f21e7c21e5cb3b7a4c5ca777c5f99df345d5874389d4763b265efc2ff3f2b123ac73641a16eb3b6fc5cd94eda099d78b483cbf3113ca9382ba36020309f8188434cfd3f3d0159c56fb089b46ea6808290a1c7db7fd6611c3bcc35e226b4e9d1ddacc1060cfea52550967b2545c7b7ffc330dbe91cafbf8bbbf04078e66c695904f9761bdc7c64912ab395292d86e7cf6302037089339b6d2c4e803350022797ad1c1d1623f1e3fe9539fc75acd714f65f72a08b161f361232e51ffec4f454c705476aeb9", 16),
    new BigInteger("010001", 16),
    new BigInteger("78e608a61a7e86b5609ef9a990d6f4e4308f2183b1bb033abed859b164f07c445ea237dbf18020d93d5595a09f8552fbf1337e3411dbc91f40b95e443081c323f5dda2bd19f6d9f484bd0cfdfaa5a53d5ce03533ee564eb3a81d7d0bb9b9fe3cb79a4ed9e2044ca64926c4c60c0433c85b0db7162ba76132f0a54ae2165256f7897cb9a984b7c164a49c77314fe6573a491bf7f33f003a38a96234a67bccb63db993173d5ea201928c407a4bd3cb4463dde4226e421e32b81de9d5a8bcbff52ce148971a03d061ee48ab5ab8d9d8d330907f228b67f3dd6e537c69639388a2081ad4624fb5f00116e399a6d33a2222b28891f1d819ecaec507228d8aa518ddfd", 16),
    new BigInteger("00f90e6616cd2e4666c97b42b0db2c24c18d156235330fb422a6e4a5f14070a99ac441b37ccfc5c70874aa09fa13342b793ef51b56a1ceabd07c915d6657b564941030f794432cf387b9b5a2f660580d6f5d5cba31a942c208103a239777172ea7e4f01c3cc330239c0a0934c26630c65a12dc0df3a9e05d2525862b627a7be62b", 16),
    new BigInteger("00c7d07adac5da71a3a01c5ffc3fe6359f70e2985c4102c9d7f3065527f702af42014712ffcec30fcdae54666c75498de02cc2620cbbb3bc5d3149cab12106291f9a2461663832d2f54583c37b31df1b29a70538b551097e7cdad6490332cb6965f392db9370798b5847f5c250000f6e774536f3dae27e8844b93c0e830a93d0ab", 16),
    new BigInteger("140dc9c09a42d89e5c28d5a4e1f0fb00aeb88310df8cab278322b40de9ef6868b2d6cb7a084cd78ae1c1f34db49025d3fc72c601c2c39e680a2fb642905b65beda52e70c84203177c34751d8dec71845d851a81869959b8404b279bd2f74a96811721803f87f7ece88ac5718341c474c676a5aa13d1378cc8de9f0c25c346fc3", 16),
    new BigInteger("2c037c4c5c70b5bf793146e3659fec07e6f1c2e5ef5c11e203a24d77b42d5f3586da8510dc16939096e9f875c39024345127b03965cf3d9ab994ab9540d4fe91fb7e30063832d9cd3536c5048a03bf13f9ba68b767d6538a6519f69341c914ba6460e105252d60c85d71810fe6337ffdbdbd5111d1fa5541ee1b9086c4f9b269", 16),
    new BigInteger("023002834562f34eeaba2375af03f8daa4dabcd9584924e48f8a216a2497ab2902e38e1d1fe170b033edbc1714a69c07687150040ddd02d47290a372eee2ffa7fde5145f1a20cc75e1c9bafbdfecd1abf9a2560f480b583b328a22975bfcb4d080d026fd2b658d7c97abc11eaa7e6cbe5db6cb8b54e48cc6f506a6d5101828d3", 16))


  val proxyToServerKeyStorePassword = "killccp"
  val proxyToServerAlias = "proxy-to-server-alias"
  val publicProxyToServerKeySpec = new RSAPublicKeySpec(
    new BigInteger("00c26504945f6c14e6e76675f843e2eb0918f30e41a13d49baa29464e185ecd9dfdf7b4a121c203f7852a5f201f44acec90000b32053e92e80e1a947d7f21e7c21e5cb3b7a4c5ca777c5f99df345d5874389d4763b265efc2ff3f2b123ac73641a16eb3b6fc5cd94eda099d78b483cbf3113ca9382ba36020309f8188434cfd3f3d0159c56fb089b46ea6808290a1c7db7fd6611c3bcc35e226b4e9d1ddacc1060cfea52550967b2545c7b7ffc330dbe91cafbf8bbbf04078e66c695904f9761bdc7c64912ab395292d86e7cf6302037089339b6d2c4e803350022797ad1c1d1623f1e3fe9539fc75acd714f65f72a08b161f361232e51ffec4f454c705476aeb9", 16),
    new BigInteger("010001", 16))

  val privateProxyToServerKeySpec = new RSAPrivateCrtKeySpec(
    new BigInteger("00c26504945f6c14e6e76675f843e2eb0918f30e41a13d49baa29464e185ecd9dfdf7b4a121c203f7852a5f201f44acec90000b32053e92e80e1a947d7f21e7c21e5cb3b7a4c5ca777c5f99df345d5874389d4763b265efc2ff3f2b123ac73641a16eb3b6fc5cd94eda099d78b483cbf3113ca9382ba36020309f8188434cfd3f3d0159c56fb089b46ea6808290a1c7db7fd6611c3bcc35e226b4e9d1ddacc1060cfea52550967b2545c7b7ffc330dbe91cafbf8bbbf04078e66c695904f9761bdc7c64912ab395292d86e7cf6302037089339b6d2c4e803350022797ad1c1d1623f1e3fe9539fc75acd714f65f72a08b161f361232e51ffec4f454c705476aeb9", 16),
    new BigInteger("010001", 16),
    new BigInteger("78e608a61a7e86b5609ef9a990d6f4e4308f2183b1bb033abed859b164f07c445ea237dbf18020d93d5595a09f8552fbf1337e3411dbc91f40b95e443081c323f5dda2bd19f6d9f484bd0cfdfaa5a53d5ce03533ee564eb3a81d7d0bb9b9fe3cb79a4ed9e2044ca64926c4c60c0433c85b0db7162ba76132f0a54ae2165256f7897cb9a984b7c164a49c77314fe6573a491bf7f33f003a38a96234a67bccb63db993173d5ea201928c407a4bd3cb4463dde4226e421e32b81de9d5a8bcbff52ce148971a03d061ee48ab5ab8d9d8d330907f228b67f3dd6e537c69639388a2081ad4624fb5f00116e399a6d33a2222b28891f1d819ecaec507228d8aa518ddfd", 16),
    new BigInteger("00f90e6616cd2e4666c97b42b0db2c24c18d156235330fb422a6e4a5f14070a99ac441b37ccfc5c70874aa09fa13342b793ef51b56a1ceabd07c915d6657b564941030f794432cf387b9b5a2f660580d6f5d5cba31a942c208103a239777172ea7e4f01c3cc330239c0a0934c26630c65a12dc0df3a9e05d2525862b627a7be62b", 16),
    new BigInteger("00c7d07adac5da71a3a01c5ffc3fe6359f70e2985c4102c9d7f3065527f702af42014712ffcec30fcdae54666c75498de02cc2620cbbb3bc5d3149cab12106291f9a2461663832d2f54583c37b31df1b29a70538b551097e7cdad6490332cb6965f392db9370798b5847f5c250000f6e774536f3dae27e8844b93c0e830a93d0ab", 16),
    new BigInteger("140dc9c09a42d89e5c28d5a4e1f0fb00aeb88310df8cab278322b40de9ef6868b2d6cb7a084cd78ae1c1f34db49025d3fc72c601c2c39e680a2fb642905b65beda52e70c84203177c34751d8dec71845d851a81869959b8404b279bd2f74a96811721803f87f7ece88ac5718341c474c676a5aa13d1378cc8de9f0c25c346fc3", 16),
    new BigInteger("2c037c4c5c70b5bf793146e3659fec07e6f1c2e5ef5c11e203a24d77b42d5f3586da8510dc16939096e9f875c39024345127b03965cf3d9ab994ab9540d4fe91fb7e30063832d9cd3536c5048a03bf13f9ba68b767d6538a6519f69341c914ba6460e105252d60c85d71810fe6337ffdbdbd5111d1fa5541ee1b9086c4f9b269", 16),
    new BigInteger("023002834562f34eeaba2375af03f8daa4dabcd9584924e48f8a216a2497ab2902e38e1d1fe170b033edbc1714a69c07687150040ddd02d47290a372eee2ffa7fde5145f1a20cc75e1c9bafbdfecd1abf9a2560f480b583b328a22975bfcb4d080d026fd2b658d7c97abc11eaa7e6cbe5db6cb8b54e48cc6f506a6d5101828d3", 16))


  override def sslManger = new SSLManager {
    def getServerSSLContext: SSLContext = createSSLContext(keyStoreType, publicServerKeySpec, privateServerKeySpec, serverKeyStorePassword, serverAlias)

    def getProxyToServerSSLContext: SSLContext = createSSLContext(keyStoreType, publicProxyToServerKeySpec, privateProxyToServerKeySpec, proxyToServerKeyStorePassword, proxyToServerAlias)
  }


  def createSSLContext(keyStoreType: String, publicKeySpec: RSAPublicKeySpec, privateKeySpec: RSAPrivateCrtKeySpec, keyStorePassword: String, alias: String): SSLContext = {
    val keyStore = KeyStore.getInstance(keyStoreType)
    keyStore.load(null, null)
    val certificate = createCertificate(publicKeySpec, privateKeySpec)
    keyStore.setKeyEntry(alias, keyFactory.generatePrivate(privateKeySpec), keyStorePassword.toCharArray(), Array[Certificate](certificate.asInstanceOf[Certificate]))
    val keyManagerFactory = KeyManagerFactory.getInstance(Security.getProperty("ssl.KeyManagerFactory.algorithm"))
    keyManagerFactory.init(keyStore, keyStorePassword.toCharArray())


    val trustKeyStore = KeyStore.getInstance(keyStoreType)
    trustKeyStore.load(null, null)
    trustKeyStore.setCertificateEntry(alias, certificate)
    val trustManagerFactory = TrustManagerFactory.getInstance(Security.getProperty("ssl.TrustManagerFactory.algorithm"))
    trustManagerFactory.init(trustKeyStore)

    val sSLContext = SSLContext.getInstance(protocol)
    sSLContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null)
    sSLContext
  }

  def createCertificate(publicKeySpec: RSAPublicKeySpec, privateKeySpec: RSAPrivateCrtKeySpec) = {


    val publicKey = keyFactory.generatePublic(publicKeySpec)
    val privateKey = keyFactory.generatePrivate(privateKeySpec)

    //
    //    ContentSigner sigGen = ...;
    //    SubjectPublicKeyInfo subPubKeyInfo = ....;
    //
    //    Date startDate = new Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000);
    //    Date endDate = new Date(System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000);
    //
    //    X509v1CertificateBuilder v1CertGen = new X509v1CertificateBuilder(
    //      new X500Name("CN=Test"),
    //      BigInteger.ONE,
    //      startDate, endDate,
    //      new X500Name("CN=Test"),
    //      subPubKeyInfo);
    //
    //    X509CertificateHolder certHolder = v1CertGen.build(sigGen);

    val v3CertGen = new X509V3CertificateGenerator()
    val serialNumber = BigInteger.valueOf(10000)
    v3CertGen.setSerialNumber(serialNumber)
    v3CertGen.setIssuerDN(new X509Principal("CN=lifecosys, OU=None, O=None L=None, C=None"))
    v3CertGen.setNotBefore(new Date(System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 30))
    v3CertGen.setNotAfter(new Date(System.currentTimeMillis() + (1000L * 60 * 60 * 24 * 365 * 10)))
    v3CertGen.setSubjectDN(new X509Principal("CN=lifecosys, OU=None, O=None L=None, C=None"))


    v3CertGen.setPublicKey(publicKey)
    v3CertGen.setSignatureAlgorithm("SHA1WithRSAEncryption")

    val certificate = v3CertGen.generate(privateKey, "BC")
    certificate.checkValidity(new Date())
    certificate.verify(publicKey)
    certificate
  }
}


class GFWProgrammaticCertificationProxyConfig extends ProgrammaticCertificationProxyConfig {
  override val getChainProxyManager: ChainProxyManager = new GFWChainProxyManager()
}

