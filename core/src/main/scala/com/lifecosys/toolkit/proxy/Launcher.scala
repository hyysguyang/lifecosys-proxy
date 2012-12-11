package com.lifecosys.toolkit.proxy

import java.net.InetSocketAddress


/**
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 12/3/12 12:50 AM
 */
object Launcher {
  def main(args: Array[String]) {
    //    ProxyServer(8080) start

    val proxy = ProxyServer(8080, false, true)
    val chainProxy = ProxyServer(8081, true, false)
    proxy.chainProxies += new InetSocketAddress(8081)

    chainProxy start

    proxy start
  }
}


