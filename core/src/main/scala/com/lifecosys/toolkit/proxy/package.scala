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

package com.lifecosys.toolkit

import org.jboss.netty.channel._
import org.jboss.netty.util.HashedWheelTimer
import collection.mutable
import org.jboss.netty.bootstrap.ClientBootstrap
import com.lifecosys.toolkit.ssl.DefaultEncryptor

/**
 *
 *
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 1/12/13 3:59 PM
 */
package object proxy {
  val DEFAULT_BUFFER_SIZE = 1024 * 8
  val timer = new HashedWheelTimer
  val hostToChannelFuture = mutable.Map[Host, Channel]()

  val encryptor = new DefaultEncryptor

  implicit def channelPipelineInitializer(f: ChannelPipeline ⇒ Unit): ChannelPipelineFactory = new ChannelPipelineFactory {
    def getPipeline: ChannelPipeline = {
      val pipeline: ChannelPipeline = Channels.pipeline()
      f(pipeline)
      pipeline
    }
  }

  implicit def channelFutureListener(f: ChannelFuture ⇒ Unit): ChannelFutureListener = new ChannelFutureListener {
    def operationComplete(future: ChannelFuture) = f(future)
  }

  def newClientBootstrap = {
    val proxyToServerBootstrap = new ClientBootstrap()
    proxyToServerBootstrap.setOption("keepAlive", true)
    proxyToServerBootstrap.setOption("connectTimeoutMillis", 60 * 1000)
    proxyToServerBootstrap
  }

}
