package com.lifecosys.toolkit.proxy

import org.jboss.netty.channel.ReceiveBufferSizePredictor
import org.jboss.netty.buffer.{ ChannelBufferFactory, ChannelBuffer }
import java.util.concurrent.Executor
import org.jboss.netty.channel.socket.nio.{ NioSocketChannel, NioWorker }
import com.typesafe.scalalogging.slf4j.Logging
import java.nio.channels.{ ClosedChannelException, SocketChannel, SelectionKey }
import java.nio.ByteBuffer
import org.jboss.netty.channel.Channels._

/**
 *
 *
 * @author Young Gu
 * @version 1.0 7/17/13 10:37 AM
 */
class ProxyNioWorker(executor: Executor) extends NioWorker(executor, null) with Logging {
  override def read(k: SelectionKey): Boolean = {
    val ch: SocketChannel = k.channel.asInstanceOf[SocketChannel]
    val channel: NioSocketChannel = k.attachment.asInstanceOf[NioSocketChannel]
    val predictor: ReceiveBufferSizePredictor = channel.getConfig.getReceiveBufferSizePredictor
    val bufferFactory: ChannelBufferFactory = channel.getConfig.getBufferFactory
    var ret: Int = 0
    var readBytes: Int = 0
    var failure: Boolean = true
    val bb: ByteBuffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE)

    try {
      def read = {
        ret = ch.read(bb)
        if (ret > 0) readBytes += ret
        ret > 0
      }

      while (read && bb.hasRemaining) {

      }

      failure = false
    } catch {
      case e: ClosedChannelException ⇒ logger.error(e.getStackTraceString) // Can happen, and does not need a user attention.
      case t: Throwable              ⇒ logger.error(t.getStackTraceString); fireExceptionCaught(channel, t)
    }

    logger.error(s"Reading buffer length: $readBytes ")

    if (readBytes > 0) {
      bb.flip
      val buffer: ChannelBuffer = bufferFactory.getBuffer(readBytes)
      buffer.setBytes(0, bb)
      buffer.writerIndex(readBytes)
      predictor.previousReceiveBufferSize(readBytes)

      //      logger.error(s"##Reading.........########${buffer.readableBytes()}#########")
      //      logger.error(Utils.formatMessage(ChannelBuffers.copiedBuffer(buffer)))
      fireMessageReceived(channel, buffer)
    }

    if (ret < 0 || failure) {
      k.cancel
      close(channel, succeededFuture(channel))
      return false
    }

    return true
  }

}
