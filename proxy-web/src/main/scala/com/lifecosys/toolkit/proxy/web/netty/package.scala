package com.lifecosys.toolkit.proxy.web

import java.util.concurrent.{ Executor, Executors }
import org.jboss.netty.channel.socket.nio.{ NioClientSocketChannelFactory, NioWorker, NioWorkerPool }

/**
 *
 *
 * @author Young Gu
 * @version 1.0 8/9/13 2:11 PM
 */
package object netty {
  val executor = Executors.newCachedThreadPool()

  val pool: NioWorkerPool = new NioWorkerPool(executor, 100) {
    override def newWorker(executor: Executor): NioWorker = {
      new NioWorker(executor, null)
    }
  }
  val clientSocketChannelFactory = new NioClientSocketChannelFactory(executor, 1, pool)
}
