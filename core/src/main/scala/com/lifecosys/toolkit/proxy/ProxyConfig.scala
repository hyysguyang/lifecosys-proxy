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
import org.jboss.netty.channel.socket.{ ClientSocketChannelFactory, ServerSocketChannelFactory }
import javax.net.ssl.SSLContext
import org.jboss.netty.channel.group.{ DefaultChannelGroup, ChannelGroup }
import collection.mutable
import org.jboss.netty.channel.socket.nio.{ NioClientSocketChannelFactory, NioServerSocketChannelFactory }
import java.util.concurrent.{ SynchronousQueue, TimeUnit, ThreadPoolExecutor }
import com.lifecosys.toolkit.ssl.{ DefaultStaticCertificationSSLManager, ProgrammaticCertificationSSLManager, SSLManager }
import com.typesafe.config.{ ConfigList, ConfigValue, ConfigFactory, Config }

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

  val chainProxies: scala.collection.mutable.ArrayBuffer[ProxyHost]

  val serverSocketChannelFactory: ServerSocketChannelFactory

  val clientSocketChannelFactory: ClientSocketChannelFactory

  val serverSSLContext: SSLContext

  val clientSSLContext: SSLContext

  val allChannels: ChannelGroup = new DefaultChannelGroup("HTTP-Proxy-Server")

  def getChainProxyManager: ChainProxyManager = new DefaultChainProxyManager

  require(!isLocal || !serverSSLEnable)
}

abstract class DefaultProxyConfig(config: Option[Config] = None) extends ProxyConfig {

  val thisConfig = config.getOrElse(ConfigFactory.load())

  val name = thisConfig.getString("name")

  val serverThreadCorePoolSize = thisConfig.getInt("proxy-server.thread.corePoolSize")
  val serverThreadMaximumPoolSize = thisConfig.getInt("proxy-server.thread.maximumPoolSize")

  val proxyToServerThreadCorePoolSize = thisConfig.getInt("proxy-server-to-remote.thread.corePoolSize")
  val proxyToServerThreadMaximumPoolSize = thisConfig.getInt("proxy-server-to-remote.thread.maximumPoolSize")

  val serverExecutor = new ThreadPoolExecutor(serverThreadCorePoolSize, serverThreadMaximumPoolSize, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable])
  val clientExecutor = new ThreadPoolExecutor(proxyToServerThreadCorePoolSize, proxyToServerThreadMaximumPoolSize, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable])

  override val port = thisConfig.getInt("port")
  override val serverSSLEnable = thisConfig.getBoolean("proxy-server.ssl.enabled")
  override val proxyToServerSSLEnable = thisConfig.getBoolean("proxy-server-to-remote.ssl.enabled")

  override val loggerLevel = InternalLogLevel.valueOf(thisConfig.getString("logger-level"))

  override val serverSocketChannelFactory = new NioServerSocketChannelFactory(serverExecutor, serverExecutor)
  override val clientSocketChannelFactory = new NioClientSocketChannelFactory(clientExecutor, clientExecutor)
  override val isLocal = thisConfig.getBoolean("local")
  import scala.collection.JavaConversions._
  override val chainProxies = {
    def createProxyHost(hostConfig: ConfigValue) = {
      val server = hostConfig.atKey("server")
      ProxyHost(Host(server.getString("server.host")), ProxyType(server.getString("server.type")))
    }
    val proxyList = thisConfig.getValue("chain-proxy").asInstanceOf[ConfigList].map(createProxyHost _).toSet.toList
    mutable.ArrayBuffer[ProxyHost](proxyList: _*)
  }

  lazy override val serverSSLContext = sslManger.getServerSSLContext

  lazy override val clientSSLContext = sslManger.getProxyToServerSSLContext

  def sslManger: SSLManager

}

class DefaultStaticCertificationProxyConfig(config: Option[Config] = None) extends DefaultProxyConfig(config) {
  override def sslManger = new DefaultStaticCertificationSSLManager {
    val serverSSLKeystorePassword = thisConfig.getString("proxy-server.ssl.keystore-password")
    val serverSSLKeystorePath = thisConfig.getString("proxy-server.ssl.keystore-path")
    val serverSSLTrustKeystorePath = thisConfig.getString("proxy-server.ssl.trust-keystore-path")

    val proxyToServerSSLKeystorePassword = thisConfig.getString("proxy-server-to-remote.ssl.keystore-password")
    val proxyToServerSSLKeystorePath = thisConfig.getString("proxy-server-to-remote.ssl.keystore-path")
    val proxyToServerSSLTrustKeystorePath = thisConfig.getString("proxy-server-to-remote.ssl.trust-keystore-path")
  }
}

class ProgrammaticCertificationProxyConfig(config: Option[Config] = None) extends DefaultProxyConfig(config) {
  override def sslManger = new ProgrammaticCertificationSSLManager
}
class GFWProgrammaticCertificationProxyConfig(config: Option[Config] = None) extends ProgrammaticCertificationProxyConfig(config) {
  override val getChainProxyManager: ChainProxyManager = new GFWChainProxyManager()
}

