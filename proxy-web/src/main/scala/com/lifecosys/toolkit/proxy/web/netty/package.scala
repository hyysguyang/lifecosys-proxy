package com.lifecosys.toolkit.proxy.web

import java.util.concurrent.Executors
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.channel.Channel

/**
 *
 *
 * @author Young Gu
 * @version 1.0 8/9/13 2:11 PM
 */
package object netty {
  val executor = Executors.newCachedThreadPool()
  val clientSocketChannelFactory = new NioClientSocketChannelFactory(executor, executor)
  val channels = scala.collection.mutable.Map[String, Channel]()
}
