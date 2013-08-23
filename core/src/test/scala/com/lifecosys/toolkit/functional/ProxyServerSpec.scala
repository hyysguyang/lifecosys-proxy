package com.lifecosys.toolkit.functional

import com.lifecosys.toolkit.proxy._
import com.typesafe.config.ConfigFactory
import java.security.Security
import org.apache.commons.io.IOUtils
import org.apache.http.client.methods.HttpGet
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jboss.netty.logging.{ Slf4JLoggerFactory, InternalLoggerFactory }
import org.scalatest._
import scala.Some

/**
 *
 *
 * @author Young Gu
 * @version 1.0 7/9/13 10:53 AM
 */

trait BaseSpec extends FeatureSpec with BeforeAndAfterAll {

  def proxyServer: Option[ProxyServer] = None
  def chainedProxyServer: Option[ProxyServer] = None

  override protected def beforeAll() {
    //    Utils.installJCEPolicy
    InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
    Security.addProvider(new BouncyCastleProvider)
    chainedProxyServer.foreach(_ start)
    proxyServer.foreach(_ start)
  }

  override protected def afterAll() {
    chainedProxyServer.foreach(_ shutdown)
    proxyServer.foreach(_ shutdown)
  }

  def scenarios {
    scenario(" should proxy http request") {
      testSimpleHttp
    }

    scenario(" should proxy https request") {
      testSimpleHttps
    }
  }

  def testSimpleHttp {
    val httpClient = createHttpClient
    val response: String = IOUtils.toString(httpClient.execute(new HttpGet("http://www.baidu.com/")).getEntity.getContent)
    Assertions.assert(response.length > 10000)
    httpClient.close()
  }

  def createHttpClient = ProxyTestUtils.createHttpClient(httpClientProxyPort)

  /**
   * Need use different proxy port for each spec since SBT will failed to bind it.
   * @return
   */
  def httpClientProxyPort: Int

  def testSimpleHttps {
    val httpClient = createHttpClient
    val response: String = IOUtils.toString(httpClient.execute(new HttpGet("https://developer.apple.com/")).getEntity.getContent)
    Assertions.assert(response.length > 10000)
    httpClient.close()

  }
}

class SimpleNetProxySpec extends BaseSpec with BeforeAndAfterAll {
  def httpClientProxyPort: Int = 19070
  override def proxyServer = {
    val netConfig = ConfigFactory.parseResources("com/lifecosys/toolkit/proxy/server/server/NetProxyServer-application.conf").withFallback(ConfigFactory.load())
    Some(ProxyServer(new ProgrammaticCertificationProxyConfig(Some(netConfig))))
  }

  feature("Net Proxy Server without chained proxy") {
    scenarios
  }
}

class SimpleChainedNetProxySpec extends BaseSpec with BeforeAndAfterAll {
  def httpClientProxyPort: Int = 19071
  override val chainedProxyServer = {
    val chainConfig = ConfigFactory.parseResources("com/lifecosys/toolkit/proxy/server/server/ChainedProxyServer-application.conf").withFallback(ConfigFactory.load())
    Some(ProxyServer(new ProgrammaticCertificationProxyConfig(Some(chainConfig))))
  }

  override def proxyServer = {
    val netConfig = ConfigFactory.parseResources("com/lifecosys/toolkit/proxy/server/server/NetProxyServerWithChainedProxy-application.conf").withFallback(ConfigFactory.load())
    Some(ProxyServer(new ProgrammaticCertificationProxyConfig(Some(netConfig))))
  }

  feature("Net Proxy Server without chained proxy") {
    scenarios
  }
}

class SimpleWebProxySpec extends BaseSpec with BeforeAndAfterAll {
  //    System.setProperty("javax.net.debug", "all")

  def httpClientProxyPort: Int = 19073
  override def proxyServer = {
    val config = ConfigFactory.parseResources("com/lifecosys/toolkit/proxy/server/server/WebProxy-application.conf").withFallback(ConfigFactory.load())
    Some(ProxyServer(new ProgrammaticCertificationProxyConfig(Some(config))))
  }
  feature("Proxy Server with chained web proxy") {
    scenarios
  }

}

//class SimpleHttpsWebProxySpec extends BaseSpec with BeforeAndAfterAll {
//  //  System.setProperty("javax.net.debug", "all")
//  def httpClientProxyPort: Int = 19061
//  override def proxyServer = {
//    val config = ConfigFactory.parseResources("com/lifecosys/toolkit/proxy/com.lifecosys.toolkit.proxy.server.server/HTTPSWebProxy-application.conf").withFallback(ConfigFactory.load())
//    val proxyConfig = new ProgrammaticCertificationProxyConfig(Some(config)) {
//      lazy override val clientSSLContext = Utils.trustAllSSLContext
//    }
//    Some(ProxyServer(proxyConfig))
//  }
//
//  feature("Proxy Server with chained HTTPS-based web proxy") {
//    scenarios
//  }
//}

