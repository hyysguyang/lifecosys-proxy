package com.lifecosys.toolkit.proxy

import java.net.Socket
import org.jboss.netty.channel._
import com.typesafe.scalalogging.slf4j.Logging
import org.jboss.netty.buffer.{ ChannelBuffers, ChannelBuffer }
import java.io.ByteArrayOutputStream
import org.apache.commons.io.IOUtils
import java.nio.channels.ClosedChannelException;

class JavaNetRelayingHandler(socket: Socket)(implicit proxyConfig: ProxyConfig)
    extends SimpleChannelUpstreamHandler with Logging {

  val phase = scala.collection.mutable.ArrayBuffer[String]("ClientHello", "ClientKeyExchange", "Finish", "verify_data", "DATA")
  var buffers = scala.collection.mutable.ArrayBuffer[ChannelBuffer]()
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.debug(s"############${e.getChannel} receive message###############\n ${Utils.formatMessage(e.getMessage)}")
    val requestMessage = e.getMessage.asInstanceOf[ChannelBuffer]
    try {

      if (phase(0) == "Finish" && requestMessage.readableBytes() > 6) {
        synchronized(phase -= phase.head)
      }

      if (phase(0) == "ClientHello" || phase(0) == "verify_data" || phase(0) == "DATA") {

        val socketOutput = socket.getOutputStream
        val socketInput = socket.getInputStream
        socketOutput.flush()

        //        val requestMessage: ChannelBuffer = requestQueue.dequeue()

        buffers.foreach {
          buffer ⇒
            logger.error(s"Write request to ${socket} --- ${socket.isConnected}\n ${Utils.hexDumpToString(buffer.array())}")
            socketOutput.write(buffer.array())
            socketOutput.flush()
        }
        buffers.clear()
        logger.error(s"Write request to ${socket} --- ${socket.isConnected}\n ${Utils.hexDumpToString(requestMessage.array())}")
        socketOutput.write(requestMessage.array())
        socketOutput.flush()
        val message = new ByteArrayOutputStream()
        IOUtils.copyLarge(socketInput, message, 0, 1)
        while (socketInput.available() > 0) {
          IOUtils.copyLarge(socketInput, message, 0, socketInput.available())
        }

        if (message.size() == 6) {
          IOUtils.copyLarge(socketInput, message, 0, 1)
          while (socketInput.available() > 0) {
            IOUtils.copyLarge(socketInput, message, 0, socketInput.available())
          }
        }
        val responseData = message.toByteArray
        logger.error(s"Receive message ${Utils.hexDumpToString(responseData)}")

        e.getChannel.write(ChannelBuffers.copiedBuffer(responseData)).addListener {
          future: ChannelFuture ⇒
            synchronized {
              phase -= phase.head
              if (phase.isEmpty) {
                phase ++= Array("ClientHello", "ClientKeyExchange", "Finish", "verify_data", "DATA")
              }
            }
            logger.debug(s"Finished write response to ${e.getChannel}")
        }

      } else {
        buffers += requestMessage
        synchronized(phase -= phase.head)
      }
    } catch {
      case e ⇒ e.printStackTrace()
    }

  }

  override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    val ch: Channel = e.getChannel
    logger.debug("CONNECT channel opened on: %s".format(ch))
    //    proxyConfig.allChannels.add(e.getChannel)
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.debug("Got closed event on : %s".format(e.getChannel))
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn("Caught exception on : %s".format(e.getChannel), e.getCause)
    e.getCause match {
      case closeException: ClosedChannelException ⇒ //Just ignore it
      case exception                              ⇒ Utils.closeChannel(e.getChannel)
    }
  }
}
