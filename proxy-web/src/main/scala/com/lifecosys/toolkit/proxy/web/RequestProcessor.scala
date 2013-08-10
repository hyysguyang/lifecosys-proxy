package com.lifecosys.toolkit.proxy.web

import com.typesafe.scalalogging.slf4j.Logging
import com.lifecosys.toolkit.proxy._
import javax.servlet.http.{ HttpServletResponse, HttpServletRequest }
import java.io.InputStream
import java.nio.ByteBuffer
import com.lifecosys.toolkit.proxy.ChannelKey

/**
 *
 *
 * @author Young Gu
 * @version 1.0 8/9/13 1:25 PM
 */
trait RequestProcessor extends Logging {
  def process(channelKey: ChannelKey, proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse)

  def readDataRecord(message: Message, socketInput: InputStream): Array[Byte] = {
    try {
      val buffer = new Array[Byte](5)
      var size = socketInput.read(buffer)
      while (size != 5) {
        size += socketInput.read(buffer, size, 5 - size)
      }

      logger.error(s"[${Thread.currentThread()} | ${message.request.getSession.getId}] - Received data header: ${Utils.hexDumpToString(buffer)}")

      val byteBuffer = new Array[Byte](5)
      buffer.copyToArray(byteBuffer)
      val dataLength = ByteBuffer.wrap(byteBuffer).getShort(3)
      logger.error(s"[${Thread.currentThread()} | ${message.request.getSession.getId}]###########5 + dataLength###################${5 + dataLength}")
      val data = new Array[Byte](5 + dataLength)
      buffer.copyToArray(data)
      size = socketInput.read(data, 5, dataLength)
      logger.error(s"[${Thread.currentThread()} | ${message.request.getSession.getId}] - Reading data: $size")
      while (size != dataLength) {
        val length = socketInput.read(data, 5 + size, dataLength - size)
        logger.error(s"[${Thread.currentThread()} | ${message.request.getSession.getId}] - Reading data: size=$size, dataLength=$dataLength, length=$length")
        if (length > 0) {
          size += length
        } else {
          logger.error("Thread.sleep(100000) Thread.sleep(100000) Thread.sleep(100000) ")
          Thread.sleep(100000)
        }
      }

      //        logger.debug(s"[${message.request.getSession.getId} | $socket] - Reading record completed: ${Utils.hexDumpToString(data)}")
      logger.error(s"[${Thread.currentThread()} | ${message.request.getSession.getId}] - Reading record completed: ${data.length}")

      return data
    } catch {
      case e: Throwable ⇒ {
        logger.error("Error", e)
        logger.error("Error")
      }
    }
    new Array[Byte](0)
  }

}

case class Message(request: HttpServletRequest, response: HttpServletResponse, proxyRequestBuffer: Array[Byte])
