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

import io.Source
import java.util.concurrent.atomic.AtomicInteger
import com.typesafe.scalalogging.slf4j.Logging

/**
 *
 *
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 1/3/13 9:22 PM
 */

trait FailedHostProcess {
  def hasFailedToProxy(uri: String): Boolean = false
  def store(hostAndPort: String)(implicit proxyConfig: ProxyConfig): Unit
  def report()(implicit proxyConfig: ProxyConfig)
}

object NullFailedHostProcess extends FailedHostProcess {
  def store(hostAndPort: String)(implicit proxyConfig: ProxyConfig) {}

  def report()(implicit proxyConfig: ProxyConfig) {}
}

object DefaultFailedHostProcess extends FailedHostProcess {
  /**
   * Need store failed request host and chained proxy server host.
   */
  val failedHosts = scala.collection.mutable.Set[String]()

  def store(hostAndPort: String)(implicit proxyConfig: ProxyConfig) = failedHosts += hostAndPort

  def report()(implicit proxyConfig: ProxyConfig) {}
}

object SmartHostFailedHostProcess extends FailedHostProcess {

  /**
   * Store extracted domain from uri
   */
  val failedHosts = scala.collection.mutable.Set[String]()

  override def hasFailedToProxy(uri: String): Boolean = failedHosts.contains(Utils.extractHostAndPort(uri)._1)

  def store(hostAndPort: String)(implicit proxyConfig: ProxyConfig) = failedHosts += hostAndPort

  def report()(implicit proxyConfig: ProxyConfig) {}
}

trait ChainProxyManager extends Logging {

  def failedHostProcess: FailedHostProcess = NullFailedHostProcess

  /**
   *
   * @param hostAndPort IP:Port
   * @param proxyConfig
   * @return
   */
  def connectFailed(hostAndPort: String)(implicit proxyConfig: ProxyConfig): Unit = logger.warn(s"Can't connect to server: $hostAndPort")

  def getConnectHost(uri: String)(implicit proxyConfig: ProxyConfig): Option[ConnectHost] = if (failedHostProcess.hasFailedToProxy(uri)) None else fetchConnectHost(uri)

  /**
   * DON'T return None for Public API..
   * @param uri
   * @param proxyConfig
   * @return
   */
  def fetchConnectHost(uri: String)(implicit proxyConfig: ProxyConfig): Option[ConnectHost]
}

class DefaultChainProxyManager extends ChainProxyManager {
  val retryStatistic = scala.collection.mutable.Map[Host, AtomicInteger]()

  override val failedHostProcess = DefaultFailedHostProcess

  /**
   * Always return Some
   *
   * @param uri
   * @param proxyConfig
   * @return
   */
  def fetchConnectHost(uri: String)(implicit proxyConfig: ProxyConfig) = proxyConfig.chainProxies.headOption match {
    case Some(proxyHost) ⇒ Some(ConnectHost(proxyHost.host, true, proxyHost.serverType))
    case None            ⇒ Some(ConnectHost(Host(uri), false))
  }

  override def connectFailed(hostAndPort: String)(implicit proxyConfig: ProxyConfig): Unit = {
    super.connectFailed(hostAndPort)
    val host = Host(hostAndPort)
    retryStatistic.get(host) match {
      case Some(retryTimes) if (retryTimes.incrementAndGet() >= 3) ⇒ {
        proxyConfig.chainProxies.remove(proxyConfig.chainProxies.lastIndexWhere(_.host == host)) //If our proxy server failed service

        failedHostProcess.store(hostAndPort)
      }
      case None ⇒ retryStatistic += host -> new AtomicInteger(1)
    }

  }

}

sealed class SmartHostsChainProxyManager extends ChainProxyManager {

  override val failedHostProcess = SmartHostFailedHostProcess

  //http://smarthosts.googlecode.com/svn/trunk/hosts
  val smartHostsResource = getClass.getResourceAsStream("/hosts.txt")

  /**
   * Domain -> IP
   */
  val smartHosts = {
    Source.fromInputStream(smartHostsResource).getLines()
      .filter(line ⇒ line.trim.length > 0 && !line.startsWith("#"))
      .map {
        line ⇒ val hd = line.split('\t'); hd(1) -> hd(0)
      }.toMap
  }

  def fetchConnectHost(uri: String)(implicit proxyConfig: ProxyConfig) = {
    val requestHost = Host(uri)
    smartHosts.get(requestHost.host).map(host ⇒ ConnectHost(Host(host, requestHost.port), false))
  }

  override def connectFailed(hostAndPort: String)(implicit proxyConfig: ProxyConfig): Unit = {
    super.connectFailed(hostAndPort)
    val host = Utils.extractHostAndPort(hostAndPort)._1
    smartHosts.filter(_._2 == host).foreach { e ⇒ failedHostProcess.store(hostAndPort) }
  }

}

class GFWListChainProxyManager extends ChainProxyManager {
  val highHitsBlockedHosts = {
    val highHitsBlockedHostsResource = getClass.getResourceAsStream("/high-hits-gfw-host-list.txt")
    scala.collection.mutable.Set(Source.fromInputStream(highHitsBlockedHostsResource).getLines().filterNot(_.startsWith("#")).toSeq: _*)
  }

  val gfwHostList = Source.fromInputStream(getClass.getResourceAsStream("/gfw-host-list.txt")).getLines().toSet.par.filterNot(_.startsWith("#"))

  /**
   * Don't need forward to upstream proxy if not blocked, just return the request host.
   *
   * @param uri
   * @param proxyConfig
   * @return
   */
  def fetchConnectHost(uri: String)(implicit proxyConfig: ProxyConfig) =
    if (isBlocked(Utils.extractHostAndPort(uri)._1)) None else Some(ConnectHost(Host(uri), false))

  def isBlocked(host: String): Boolean = {
    def matchHost(gfwHost: String) = {
      gfwHost == host || host.endsWith("." + gfwHost)
    }

    highHitsBlockedHosts.exists(matchHost _) || {
      val blocked = gfwHostList.exists(matchHost(_))
      if (blocked) highHitsBlockedHosts += host
      blocked
    }
  }

}

class GFWChainProxyManager extends ChainProxyManager {

  val defaultChainProxyManager = new DefaultChainProxyManager
  val smartHostsChainProxyManager = new SmartHostsChainProxyManager
  val gfwListChainProxyManager = new GFWListChainProxyManager

  def gfwHostList = Source.fromInputStream(getClass.getResourceAsStream("/gfw-host-list.txt")).getLines().toSet.par.filterNot(_.startsWith("#"))

  def fetchConnectHost(uri: String)(implicit proxyConfig: ProxyConfig) = {
    Some(smartHostsChainProxyManager.getConnectHost(uri) getOrElse {
      gfwListChainProxyManager.getConnectHost(uri) getOrElse defaultChainProxyManager.getConnectHost(uri).get
    })
  }

  override def connectFailed(hostAndPort: String)(implicit proxyConfig: ProxyConfig) = {
    super.connectFailed(hostAndPort)
    defaultChainProxyManager.connectFailed(hostAndPort)
    smartHostsChainProxyManager.connectFailed(hostAndPort)
    gfwListChainProxyManager.connectFailed(hostAndPort)
  }

}
