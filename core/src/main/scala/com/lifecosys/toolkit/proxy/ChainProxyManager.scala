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

import org.jboss.netty.handler.codec.http.HttpRequest
import java.net.InetSocketAddress
import io.Source

/**
 *
 *
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 1/3/13 9:22 PM
 */


trait ChainProxyManager {
  def getConnectHost(request: HttpRequest)(implicit proxyConfig: ProxyConfig): Tuple2[InetSocketAddress, Boolean]
}


class DefaultChainProxyManager extends ChainProxyManager {
  def getConnectHost(request: HttpRequest)(implicit proxyConfig: ProxyConfig): Tuple2[InetSocketAddress, Boolean] = {
    proxyConfig.chainProxies.headOption.map(_ -> true).getOrElse((Utils.extractHost(request.getUri), false))
  }
}


class GFWChainProxyManager extends ChainProxyManager {
  def smartHostsResource = getClass.getResourceAsStream("/hosts.txt")

  val smartHosts = {
    Source.fromInputStream(smartHostsResource).getLines().filter(line => line.trim.length > 0 && !line.startsWith("#")).map {
      line => val hd = line.split('\t'); (hd(1).hashCode, hd(0))
    }.toMap
  }

  val highHitsBlockedHosts = scala.collection.mutable.Set(Source.fromInputStream(getClass.getResourceAsStream("/high-hits-gfw-host-list.txt")).getLines().filterNot(_.startsWith("#")).toSeq: _*)

  def gfwHostList = Source.fromInputStream(getClass.getResourceAsStream("/gfw-host-list.txt")).getLines().toSet.par.filterNot(_.startsWith("#"))

  def getConnectHost(request: HttpRequest)(implicit proxyConfig: ProxyConfig): Tuple2[InetSocketAddress, Boolean] = {
    val hostPort = Utils.extractHostAndPort(request.getUri)
    smartHosts.get(hostPort._1.trim.hashCode).map(new InetSocketAddress(_, hostPort._2.toInt) -> false).getOrElse {
      if (!isBlocked(hostPort._1.trim))
        (new InetSocketAddress(hostPort._1, hostPort._2.toInt), false)
      else
        proxyConfig.chainProxies.headOption.map(_ -> true).getOrElse((Utils extractHost request.getUri, false))
    }
  }

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
