package com.lifecosys.toolkit.proxy.server

import com.lifecosys.toolkit.proxy._
import org.jboss.netty.logging.{ Slf4JLoggerFactory, InternalLoggerFactory }
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import com.typesafe.config.ConfigFactory
import scala.Some
import com.lifecosys.toolkit.logging.Logger

/**
 *
 *
 * @author Young Gu
 * @version 1.0 6/6/13 3:46 PM
 */
object LittleProxy {
  logger = Logger()
  Utils.installJCEPolicy
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  Security.addProvider(new BouncyCastleProvider)

  def main(args: Array[String]) {

    val config = ConfigFactory.load()
    val proxyConfig = new ProgrammaticCertificationProxyConfig(Some(config))

    val localConfig = ConfigFactory.parseString(
      """
         |local=true
         |chain-proxy{
         |   host = "localhost 8081"
         |}
         |
         |proxy-server{
         |    thread {
         |        corePoolSize = 10
         |        maximumPoolSize = 30
         |    }
         |    ssl {
         |            enabled = false
         |    }
         |
         |}
         |
         |proxy-server-to-remote{
         |
         |    thread {
         |        corePoolSize = 10
         |        maximumPoolSize = 30
         |    }
         |    ssl {
         |            enabled = true
         |    }
         |}
       """.stripMargin).withFallback(config)

    val chainedProxyConfig = ConfigFactory.parseString(
      """
         |local=false
         |chain-proxy{
         |}
         |
         |proxy-server{
         |    thread {
         |        corePoolSize = 10
         |        maximumPoolSize = 30
         |    }
         |    ssl {
         |            enabled = true
         |    }
         |
         |}
         |
         |proxy-server-to-remote{
         |
         |    thread {
         |        corePoolSize = 10
         |        maximumPoolSize = 30
         |    }
         |    ssl {
         |            enabled = false
         |    }
         |}
       """.stripMargin).withFallback(config)

    val proxy = new LittleProxyServer(8080)(new GFWProgrammaticCertificationProxyConfig(Some(localConfig)))
    val server = new LittleProxyServer(8081)(new ProgrammaticCertificationProxyConfig(Some(chainedProxyConfig)))

    server.start()

    proxy.start()

  }

}

