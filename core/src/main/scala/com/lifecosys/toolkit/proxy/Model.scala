package com.lifecosys.toolkit.proxy

import java.net.InetSocketAddress
import com.lifecosys.toolkit.dns.DnsSec

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

  def apply(socketAddress: InetSocketAddress): Host = Host(socketAddress.getHostString, socketAddress.getPort)
}

case class Host(host: String, port: Int, ip: Option[String] = None) {
  require(!host.isEmpty)
  require(port > 0 && port < 65535)
  lazy val socketAddress = ip match {
    case Some(ip) ⇒ new InetSocketAddress(ip, port)
    case None     ⇒ DnsSec.newInetSocketAddress(host, port, true)
  }
  override def toString: String = s"$host:$port"
}
object ProxyType {
  def apply(proxyType: String) = proxyType.toLowerCase() match {
    case "net" ⇒ DefaultProxyType
    case "web" ⇒ WebProxyType
    case _     ⇒ NoneProxyType
  }
}
sealed trait ProxyType
case object NoneProxyType extends ProxyType
case object DefaultProxyType extends ProxyType
case object WebProxyType extends ProxyType

case class ProxyHost(host: Host, serverType: ProxyType = DefaultProxyType)
case class ConnectHost(host: Host, needForward: Boolean, serverType: ProxyType = NoneProxyType)

case class ChannelKey(sessionId: String, proxyHost: Host)

case class Header(name: String)
object ProxyHostHeader extends Header("X-PH")
object ProxyRequestType extends Header("X-PRT")

case class RequestType(value: Byte)
object HTTP extends RequestType(0)
object HTTPS extends RequestType(1)