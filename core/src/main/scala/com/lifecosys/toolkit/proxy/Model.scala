package com.lifecosys.toolkit.proxy

import java.net.InetSocketAddress

/**
 *
 *
 * @author Young Gu
 * @version 1.0 6/27/13 1:17 PM
 */
object Host {
  def apply(uri: String): Host = {
    val hostAndPort = Utils.extractHostAndPort(uri)
    Host(hostAndPort._1, hostAndPort._2)
  }
}

case class Host(host: String, port: Int) {
  require(!host.isEmpty)
  require(port > 0 && port < 65535)
  val socketAddress = new InetSocketAddress(host, port)
  override def toString: String = s"$host:$port"
}
object ProxyType {
  def apply(proxyType: String) = proxyType.toLowerCase() match {
    case "net" ⇒ DefaultProxyType
    case "web" ⇒ WebProxyType
  }
}
sealed trait ProxyType
case object NoneProxyType extends ProxyType
case object DefaultProxyType extends ProxyType
case object WebProxyType extends ProxyType
case class ProxyHost(host: Host, serverType: ProxyType = DefaultProxyType)
case class ConnectHost(host: Host, needForward: Boolean, serverType: ProxyType = NoneProxyType)