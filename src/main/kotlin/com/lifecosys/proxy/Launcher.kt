@file:JvmName("Launcher")
package com.lifecosys.proxy

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.*

/**
 *
 * @author <a href="mailto:hyysguyang@gmail.com">Young Gu</a>
 */


fun main(args: Array<String>) {

//    DefaultSelfSignedSslEngineSource(File("lifecosys-keystore.jks"))


    println(Config::class.java.getResource("/lifecosys-keystore.jks"))

    val properties = Config.properties
    val name = properties.getProperty("proxy.name")
    val port = properties.getProperty("proxy.port").toInt()

    if (properties.getProperty("proxy.local","false").toBoolean()) {
        val chainProxy = chainProxy(properties)
        val localProxy = LocalProxy(name, port, chainProxy,properties.getProperty("ssl.keystore.password"))
        localProxy.start()
    } else {
        RemoteServer(name, port,properties.getProperty("ssl.keystore.password")).start()
    }


}

private fun chainProxy(properties: Properties): InetSocketAddress {
    val host = properties.getProperty("proxy.chain.host")
    val port = properties.getProperty("proxy.chain.port").toInt()
    val chaindProxy = InetSocketAddress(InetAddress.getByName(host), port)
    return chaindProxy
}

