package com.lifecosys.toolkit.proxy

import java.net.InetSocketAddress
import org.jboss.netty.handler.codec.http.Cookie
import org.jboss.netty.buffer.ChannelBuffer

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

trait HttpsPhase {
  def move: HttpsPhase
}
case object Init extends HttpsPhase {
  def move: HttpsPhase = Connect
}
case object Connect extends HttpsPhase {
  def move: HttpsPhase = ClientHello
}
case object ClientHello extends HttpsPhase {
  def move: HttpsPhase = ClientKeyExchange
}
case object ClientKeyExchange extends HttpsPhase {
  def move: HttpsPhase = ClientFinish
}
case object ClientFinish extends HttpsPhase {
  def move: HttpsPhase = ClientVerifyData
}
case object ClientVerifyData extends HttpsPhase {
  def move: HttpsPhase = TransferData
}
case object TransferData extends HttpsPhase {
  def move: HttpsPhase = throw new UnsupportedOperationException("TransferData phase can't move forward.")
}

case class HttpsState(sessionId: Option[Cookie] = None, var phase: HttpsPhase = Connect)

case class DataHolder(length: Int, var buffer: ChannelBuffer) {
  def ready = buffer.readableBytes() >= length
  def contentLength = Math.max(length, buffer.readableBytes())
}

case class Header(name: String)
object ResponseCompleted extends Header("X-PRC")
object ProxyHostHeader extends Header("X-PH")
object ProxyRequestType extends Header("X-PRT")

case class RequestType(value: Byte)
object HTTP extends RequestType(0)
object HTTPS extends RequestType(1)