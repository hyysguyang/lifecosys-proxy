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

  val lines = scala.io.Source.fromInputStream(getClass.getResourceAsStream("/hosts.txt")).getLines()
  val smartHosts = lines.filter(line => line.trim.length > 0 && !line.startsWith("#")).map {
    line => val hd = line.split('\t'); (hd(1), hd(0))
  }.toMap

  val gfwList = new GFWList()
  gfwList.parseRules

  def getConnectHost(request: HttpRequest)(implicit proxyConfig: ProxyConfig): Tuple2[InetSocketAddress, Boolean] = {
    val hostPort = Utils.extractHostAndPort(request.getUri)
    if (!gfwList.isBlocked(request.getUri))
      (new InetSocketAddress(hostPort._1, hostPort._2.toInt), false)
    else
      smartHosts.get(hostPort._1).map(new InetSocketAddress(_, hostPort._2.toInt) -> false).getOrElse {
        proxyConfig.chainProxies.headOption.map(_ -> true).getOrElse((Utils extractHost request.getUri, false))
      }

  }
}
