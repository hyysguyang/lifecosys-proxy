/*
 * ===Begin Copyright Notice===
 *
 * NOTICE
 *
 * THIS SOFTWARE IS THE PROPERTY OF AND CONTAINS CONFIDENTIAL INFORMATION OF
 * LIFECOSYS AND/OR ITS AFFILIATES OR SUBSIDIARIES AND SHALL NOT BE DISCLOSED
 * WITHOUT PRIOR WRITTEN PERMISSION. LICENSED CUSTOMERS MAY COPY AND ADAPT
 * THIS SOFTWARE FOR THEIR OWN USE IN ACCORDANCE WITH THE TERMS OF THEIR
 * SOFTWARE LICENSE AGREEMENT. ALL OTHER RIGHTS RESERVED.
 *
 * (c) COPYRIGHT 2013 LIFECOCYS. ALL RIGHTS RESERVED. THE WORD AND DESIGN
 * MARKS SET FORTH HEREIN ARE TRADEMARKS AND/OR REGISTERED TRADEMARKS OF
 * LIFECOSYS AND/OR ITS AFFILIATES AND SUBSIDIARIES. ALL RIGHTS RESERVED.
 * ALL LIFECOSYS TRADEMARKS LISTED HEREIN ARE THE PROPERTY OF THEIR RESPECTIVE
 * OWNERS.
 *
 * ===End Copyright Notice===
 */

package com.lifecosys.toolkit


import android.app.Service
import android.content.Intent
import android.os._
import android.widget.Toast
import org.slf4j.LoggerFactory
import proxy.{SSLManager, DefaultProxyConfig, ProxyServer}
import scala.None
import com.typesafe.config.{Config, ConfigFactory}
import java.io.InputStream
import java.security.{SecureRandom, KeyStore}
import javax.net.ssl.{SSLContext, TrustManagerFactory, KeyManagerFactory}


/**
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 12/14/12 4:11 PM
 */
class ProxyService extends Service {
  val logger = LoggerFactory.getLogger(classOf[ProxyService])
  var proxyServer: ProxyServer.Proxy = null
  val isRunning = false
  var mServiceLooper: Looper = null
  var mServiceHandler: ServiceHandler = null

  // Handler that receives messages from the thread
  class ServiceHandler(looper: Looper) extends Handler(looper) {

    override def handleMessage(msg: Message) {
      logger.error("Proxy service starting..........{}.................", msg)
      proxyServer = new ProxyServer.Proxy(new DefaultProxyConfig {


        override def serverSSLManager = clientSSLManager
        override def clientSSLManager = {
          new SSLManager {
            override val keyStorePassword = null
            override val keyManagerKeyStoreInputStream = null
//            override val keyStorePassword = proxyToServerSSLKeystorePassword
//            override val keyManagerKeyStoreInputStream = classOf[DefaultProxyConfig].getResourceAsStream(proxyToServerSSLKeystorePath)
            override val trustKeyStorePassword = null
            override val trustManagerKeyStoreInputStream = null
//            override val trustKeyStorePassword = proxyToServerSSLKeystorePassword
//            override val trustManagerKeyStoreInputStream = classOf[DefaultProxyConfig].getResourceAsStream(proxyToServerSSLTrustKeystorePath)

            override def getSSLContext = {
              //FIXME: Trust all client, but we need trust manager
//              val keyStore: KeyStore = KeyStore.getInstance("BKS")
//              keyStore.load(keyManagerKeyStoreInputStream, keyStorePassword.toCharArray)
//              val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("X509")
//              keyManagerFactory.init(keyStore, keyStorePassword.toCharArray)

              val trustKeyStore: KeyStore = KeyStore.getInstance("BKS")
              trustKeyStore.load(getResources.openRawResource(R.raw.proxy_server_android_trust_keystore), "killccp-server".toCharArray)
              val trustManagerFactory: TrustManagerFactory = TrustManagerFactory.getInstance("X509")
              trustManagerFactory.init(trustKeyStore)

              val clientContext = SSLContext.getInstance("SSL")
              clientContext.init(null, trustManagerFactory.getTrustManagers, new SecureRandom)
              clientContext
            }
          }
        }
      }
      )

      proxyServer.start

    }
  }

  override def onCreate() {
    val thread = new HandlerThread("lifecosys-proxy-service-thread", Process.THREAD_PRIORITY_BACKGROUND)
    thread.start()
    mServiceLooper = thread.getLooper()
    mServiceHandler = new ServiceHandler(mServiceLooper)
  }

  override def onStartCommand(intent: Intent, flags: Int, startId: Int) = {
    Toast.makeText(this, "Proxy service starting...........................", Toast.LENGTH_LONG).show()

    // For each start request, send a message to start a job and deliver the
    // start ID so we know which request we're stopping when we finish the job
    val msg = mServiceHandler.obtainMessage()
    msg.arg1 = startId
    mServiceHandler.sendMessage(msg)

    Service.START_STICKY
  }

  def onBind(intent: Intent): IBinder = null

  override def onDestroy() {
    logger.error("Proxy service shudown..............................")
    if (proxyServer != null) {
      proxyServer.shutdown
    }
    Toast.makeText(this, "Proxy service shudown..............................", Toast.LENGTH_LONG).show()
  }
}