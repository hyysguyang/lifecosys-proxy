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

package com.lifecosys.toolkit.proxy.server

import com.typesafe.config.ConfigFactory
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jboss.netty.logging.{ Slf4JLoggerFactory, InternalLoggerFactory }
import com.lifecosys.toolkit.proxy._
import scala.Some

/**
 *
 *
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 12/15/12 2:44 AM
 */
object ProxyServerLauncher {

  def main(args: Array[String]) {
    //    System.setProperty("javax.net.debug", "all")
    Utils.installJCEPolicy
    InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
    Security.addProvider(new BouncyCastleProvider)

    val config = ConfigFactory.load()
    val proxyConfig = if (config.getBoolean("local"))
      new GFWStaticCertificationProxyConfig(Some(config))
    else
      new DefaultStaticCertificationProxyConfig(Some(config))
    ProxyServer(proxyConfig).start
  }

}

