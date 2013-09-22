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
import org.jboss.netty.buffer.{ ChannelBuffers, ChannelBuffer }
import java.nio.charset.Charset
import org.parboiled.common.Base64
import org.jboss.netty.handler.timeout.{ IdleStateEvent, IdleStateAwareChannelHandler, IdleStateHandler }
import java.util.{ Timer, TimerTask }

/**
 *
 *
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 1/12/13 3:59 PM
 */
package object proxy {
  val DEFAULT_BUFFER_SIZE = 1024 * 8
  val UTF8: Charset = Charset.forName("UTF-8")
  val base64 = Base64.custom()
  val nettyTimer = new HashedWheelTimer
  val timer = new Timer
  val hostToChannelFuture = mutable.Map[Host, Channel]()

  val encryptor = new DefaultEncryptor

  //  val requests = scala.collection.mutable.ArrayBuffer[Tuple2[String, String]]()
  //  val requestsssss = scala.collection.mutable.ArrayBuffer[String]()

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

  implicit def bufferToArray(buffer: ChannelBuffer) = {
    val data = new Array[Byte](buffer.readableBytes())
    buffer.readBytes(data)
    data
  }

  implicit def arrayToBuffer(data: Array[Byte]) = ChannelBuffers.wrappedBuffer(data)

  def newClientBootstrap = {
    val proxyToServerBootstrap = new ClientBootstrap()
    proxyToServerBootstrap.setOption("keepAlive", true)
    proxyToServerBootstrap.setOption("connectTimeoutMillis", 60 * 1000)
    proxyToServerBootstrap
  }

  def addIdleChannelHandler(pipeline: ChannelPipeline) = {
    pipeline.addLast("idleHandler", new IdleStateHandler(nettyTimer, 0, 0, 120))
    pipeline.addLast("idleStateAwareHandler", new IdleStateAwareChannelHandler {
      override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) = Utils.closeChannel(e.getChannel)
    })
  }

}
