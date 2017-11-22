package com.lifecosys.proxy


import org.littleshoot.proxy.ChainedProxyAdapter
import org.littleshoot.proxy.SslEngineSource
import org.littleshoot.proxy.TransportProtocol
import org.littleshoot.proxy.impl.DefaultHttpProxyServer
import org.slf4j.LoggerFactory
import java.io.FileInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.Security
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.*

/**
 *
 * @author <a href="mailto:hyysguyang@gmail.com">Young Gu</a>
 * @author <a href="mailto:guyang@lansent.com">Young Gu</a>
 */


class Config {
    companion object {
        val keystoreFile = javaClass.getResourceAsStream("/lifecosys-keystore.jks")
        val properties = loadConfig()
        private fun loadConfig(): Properties {
            val properties = Properties()
            properties.load(javaClass.getResourceAsStream("/config.properties"))
            return properties
        }
    }
}


class RemoteServer(name: String, port: Int, keystorePassword: String) {
    private val sslEngineSource = DefaultSelfSignedSslEngineSource(Config.keystoreFile, keystorePassword)
    private val remoteProxyServer =
            DefaultHttpProxyServer.bootstrap()
                    .withName(name)
                    .withPort(port)
                    .withAllowLocalOnly(false)
                    .withTransportProtocol(TransportProtocol.TCP)
                    .withSslEngineSource(sslEngineSource)

    fun start() = remoteProxyServer.start()
}


class LocalProxy(name: String, port: Int, val chainedProxy: InetSocketAddress, keystorePassword: String) {
    private val sslEngineSource = DefaultSelfSignedSslEngineSource(Config.keystoreFile, keystorePassword)
    private val chainProxy = object : ChainedProxyAdapter() {
        override fun getChainedProxyAddress(): InetSocketAddress = chainedProxy
        override fun requiresEncryption(): Boolean = true
        override fun newSslEngine(): SSLEngine = sslEngineSource.newSslEngine()
    }

    private val proxy = DefaultHttpProxyServer.bootstrap()
            .withName(name)
            .withPort(port)
            .withAllowLocalOnly(false)
            .withChainProxyManager { httpRequest, chainedProxies -> chainedProxies.add(chainProxy) }

    fun start() = proxy.start()
}


class DefaultSelfSignedSslEngineSource(val keyStore: InputStream = FileInputStream("lifecosys-keystore.jks"), val password: String = "changeme", val trustAllServers: Boolean = false, val sendCerts: Boolean = true) : SslEngineSource {
    private val logger = LoggerFactory.getLogger(DefaultSelfSignedSslEngineSource::class.java)
    private val protocol = "tls"

    private val sslContext: SSLContext

    init {
        sslContext = initializeSSLContext()
    }

    override fun newSslEngine(): SSLEngine = sslContext.createSSLEngine()

    override fun newSslEngine(peerHost: String, peerPort: Int): SSLEngine = sslContext.createSSLEngine(peerHost, peerPort)


    private fun initializeSSLContext(): SSLContext {
        var algorithm: String? = Security
                .getProperty("ssl.KeyManagerFactory.algorithm")
        if (algorithm == null) {
            algorithm = "SunX509"
        }

        val ks = KeyStore.getInstance("JKS")
        // ks.load(new FileInputStream("keystore.jks"),
        // "changeit".toCharArray());
        ks.load(keyStore, password.toCharArray())

        // Set up key manager factory to use our key store
        val kmf = KeyManagerFactory.getInstance(algorithm)
        kmf.init(ks, password.toCharArray())

        // Set up a trust manager factory to use our key store
        val tmf = TrustManagerFactory
                .getInstance(algorithm)
        tmf.init(ks)

        var trustManagers: Array<TrustManager>? = null
        if (!trustAllServers) {
            trustManagers = tmf.trustManagers
        } else {
            trustManagers = arrayOf(object : X509TrustManager {
                // TrustManager that trusts all servers
                override fun checkClientTrusted(arg0: Array<X509Certificate>, arg1: String) {
                }

                override fun checkServerTrusted(arg0: Array<X509Certificate>, arg1: String) {}

                override fun getAcceptedIssuers(): Array<X509Certificate>? {
                    return null
                }
            })
        }

        var keyManagers: Array<KeyManager>? = null
        if (sendCerts) {
            keyManagers = kmf.keyManagers
        } else {
            keyManagers = arrayOf()
        }

        // Initialize the SSLContext to work with our key managers.
        val sslContext = SSLContext.getInstance(protocol)
        sslContext.init(keyManagers, trustManagers, null)

        return sslContext


    }


}

