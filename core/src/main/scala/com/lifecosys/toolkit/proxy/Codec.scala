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

package com.lifecosys.toolkit.proxy

import com.typesafe.scalalogging.LazyLogging
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.channel._
import org.jboss.netty.buffer.{ ChannelBuffer, ChannelBuffers }
import org.jboss.netty.handler.codec.compression.{ ZlibEncoder, ZlibDecoder }
import org.jboss.netty.handler.codec.oneone.{ OneToOneDecoder, OneToOneEncoder }
import org.apache.commons.io.IOUtils
import com.lifecosys.toolkit.proxy.WebProxy.RequestData
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicInteger
import org.jboss.netty.handler.codec.frame.FrameDecoder

/**
 *
 *
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 1/1/13 5:50 PM
 */

class InnerHttpChunkAggregator(maxContentLength: Int = DEFAULT_BUFFER_SIZE * 8) extends HttpChunkAggregator(maxContentLength) {
  var cumulatedThunk: Option[HttpChunk] = None

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    e.getMessage match {
      case response: HttpMessage ⇒ ctx.sendUpstream(e)
      case chunk: HttpChunk if chunk.isLast ⇒ {
        if (cumulatedThunk isDefined) {
          Channels.fireMessageReceived(ctx, cumulatedThunk.get, e.getRemoteAddress)
          cumulatedThunk = None
        }
        ctx.sendUpstream(e)
      }
      case chunk: HttpChunk ⇒ {

        if (!cumulatedThunk.isDefined)
          cumulatedThunk = Some(chunk)
        else
          cumulatedThunk.get.setContent(ChannelBuffers.wrappedBuffer(cumulatedThunk.get.getContent, chunk.getContent))

        if (cumulatedThunk.get.getContent.readableBytes() > maxContentLength) {
          Channels.fireMessageReceived(ctx, cumulatedThunk.get, e.getRemoteAddress)
          cumulatedThunk = None
        }
      }
      case _ ⇒ ctx.sendUpstream(e)
    }
  }
}

/**
 * We need it to wrapper the buffer byte array to avoid the padding and block size process.
 * @param data
 */
sealed case class EncryptDataWrapper(data: Array[Byte])

class EncryptEncoder extends OneToOneEncoder {
  override def encode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = if (msg.isInstanceOf[ChannelBuffer])
    EncryptDataWrapper(Utils.cryptor.encrypt(ChannelBuffers.copiedBuffer(msg.asInstanceOf[ChannelBuffer]).array()))
  else
    msg
}

class DecryptDecoder extends OneToOneDecoder {
  def decode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = msg match {
    case EncryptDataWrapper(data) ⇒ ChannelBuffers.copiedBuffer(Utils.cryptor.decrypt(data))
    case _                        ⇒ msg
  }
}

class IgnoreEmptyBufferZlibEncoder extends ZlibEncoder {
  override def encode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = msg match {
    case cb: ChannelBuffer if (cb.hasArray) ⇒ super.encode(ctx, channel, msg).asInstanceOf[ChannelBuffer]
    case _                                  ⇒ msg
  }
}

class IgnoreEmptyBufferZlibDecoder extends ZlibDecoder {
  override def decode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = msg match {
    case cb: ChannelBuffer if (cb.hasArray) ⇒ super.decode(ctx, channel, msg).asInstanceOf[ChannelBuffer]
    case _                                  ⇒ msg
  }
}

object WebProxy {

  case object Close

  case object PrepareResponse
  case object FinishResponse
  case object CompleteRelayDataRequest
  case object TickResponse

  object RequestData {

    def apply(data: ChannelBuffer): RequestData = {
      require(data.readableBytes() > 2, "The data must be > 2 since the data should include requestID and content.")
      val requestIDBuffer = new Array[Byte](data.readByte())
      data.readBytes(requestIDBuffer)
      val requestID = new String(requestIDBuffer, UTF8)
      RequestData(requestID, data)
    }

    def toBuffer(requestData: RequestData) = {

      val ri = requestData.requestID.getBytes(UTF8)
      val requestIDBuffer = ChannelBuffers.dynamicBuffer(ri.length + 1)
      requestIDBuffer.writeByte(ri.length)
      requestIDBuffer.writeBytes(ri)
      ChannelBuffers.wrappedBuffer(requestIDBuffer, requestData.request)
    }
  }
  case class RequestData(requestID: String = "", request: ChannelBuffer = ChannelBuffers.EMPTY_BUFFER)

  def createWrappedRequest(connectHost: ConnectHost, proxyHost: Host, jsessionidCookie: Option[String] = None) = {
    val wrappedRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/proxy")
    wrappedRequest.setHeader(HttpHeaders.Names.HOST, connectHost.host.host)
    wrappedRequest.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE)
    wrappedRequest.setHeader(HttpHeaders.Names.USER_AGENT, "LTPC")

    wrappedRequest
  }
}

case class RequestIndex(requestID: String, seqID: AtomicInteger, pending: scala.collection.mutable.Map[Int, ChannelBuffer])
case class RequestInfo(requestID: String, sequenceID: AtomicInteger = new AtomicInteger)
trait HttpsRequestIndexManager {
  protected val cachedChannelFutures = scala.collection.mutable.Map[String, RequestIndex]()
  def get(requestID: String) = synchronized(cachedChannelFutures.get(requestID))

  def add(requestID: String, channel: RequestIndex) = synchronized(cachedChannelFutures += requestID -> channel)

  def remove(requestID: String) = synchronized(cachedChannelFutures.remove(requestID))
  override def toString: String = {
    s"Requests: ${cachedChannelFutures.size} pending\n" + cachedChannelFutures.mkString("\n")
  }
}

object DefaultHttpsRequestIndexManager extends HttpsRequestIndexManager

class WebProxyHttpRequestDecoder extends OneToOneDecoder with LazyLogging {
  def decode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = {
    msg match {
      //      case httpRequest: HttpRequest ⇒ httpRequest.getContent
      case httpRequest: HttpRequest ⇒
        logger.debug(s"[$channel] - Receive raw request $httpRequest")
        val requestBuffer: ChannelBuffer = arrayToBuffer(encryptor.decrypt(httpRequest.getContent))
        val requestData = RequestData(requestBuffer)
        val requestID = requestData.requestID

        DefaultHttpsRequestManager.get(requestID) match {
          case Some(remoteChannel) ⇒
            if (DefaultHttpsRequestIndexManager.get(requestID).isEmpty) {
              DefaultHttpsRequestIndexManager.add(requestID, RequestIndex(requestID, new AtomicInteger(1), scala.collection.mutable.Map[Int, ChannelBuffer]()))
            }

            val requestIndex: RequestIndex = DefaultHttpsRequestIndexManager.get(requestID).get
            val seqId = requestIndex.seqID

            if (httpRequest.getHeader("x-i").toInt > seqId.get()) {
              logger.info(s"[$requestID] - Pending NO ${httpRequest.getHeader("x-i").toInt} request to wait previous request arrive.")
              synchronized(requestIndex.pending += httpRequest.getHeader("x-i").toInt -> requestData.request)
            } else {
              require(httpRequest.getHeader("x-i").toInt == seqId.get())
              remoteChannel.write(requestData.request).addListener {
                writeFuture: ChannelFuture ⇒
                  logger.debug(s"[${channel}] - Finished writing request: ${seqId.get()} to remote server.")
                  writeRequestToRemte
                  logger.debug(s"[${channel}] - completed...")
              }

              seqId.incrementAndGet()

              def writePendingRequest(buffer: ChannelBuffer) {
                remoteChannel.write(buffer).addListener {
                  writeFuture: ChannelFuture ⇒
                    logger.debug(s"[${channel}] - Finished writing request: ${seqId.get()} to remote server.")
                    synchronized(requestIndex.pending - seqId.get())
                    seqId.incrementAndGet()
                    writeRequestToRemte
                    logger.debug(s"[${channel}] - completed...")
                }
              }

              def writeRequestToRemte {
                requestIndex.pending.get(seqId.get()) match {
                  case Some(buffer) ⇒ writePendingRequest(buffer)
                  case None         ⇒ logger.debug(s"[${channel}] - Writing next request: ${seqId.get()}")
                }
              }

            }

            channel.write(WebProxy.CompleteRelayDataRequest).addListener {
              writeFuture: ChannelFuture ⇒
                logger.debug(s"[${channel}] - finished writing response completed...")
            }

            null

          case None ⇒
            logger.debug(s"[$channel] - Initialize request $requestID, request content: ${Utils.formatMessage(requestData.request)}")
            channel.setAttachment(requestID)
            requestData.request

        }

      case _ ⇒ throw new RuntimeException("Unknown message.")
    }
  }
}

class NettyWebProxyHttpRequestEncoder(connectHost: ConnectHost, proxyHost: Host, browserChannel: Channel)
    extends HttpRequestEncoder with LazyLogging {

  override def encode(ctx: ChannelHandlerContext, channel: Channel, msg: Any): AnyRef = {

    logger.debug(s"[$channel] - Prepare request to WebProxy for ${channel.getAttachment}")

    val requestInfo = channel.getAttachment.asInstanceOf[RequestInfo]

    def setContent(wrappedRequest: DefaultHttpRequest, content: ChannelBuffer) = {
      logger.debug(s"Proxy request:\n ${Utils.formatMessage(content)}")
      //We may get CompositeChannelBuffer,such as for HttpRequest with content.
      val encryptedData: ChannelBuffer = encryptor.encrypt(content)
      wrappedRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, encryptedData.readableBytes().toString)
      wrappedRequest.setContent(encryptedData)
    }
    val toBeSentMessage = msg match {
      case request: HttpRequest ⇒ {

        //        logger.error(s">>>>>>>>>>>>>>>>>>>>>>>>> Send request: ${channel.getAttachment} --- ${request.getUri}")
        val encodedProxyRequest = super.encode(ctx, channel, request).asInstanceOf[ChannelBuffer]
        val wrappedRequest = WebProxy.createWrappedRequest(connectHost, proxyHost)
        //        logger.error(s"#######${browserChannel.getAttachment} - Send data ${encodedProxyRequest.readableBytes()}##########################")
        setContent(wrappedRequest, RequestData.toBuffer(RequestData(requestInfo.requestID, encodedProxyRequest)))
        wrappedRequest
      }
      case buffer: ChannelBuffer if buffer.readableBytes() == 0 ⇒ buffer //Process for close flush buffer.
      case buffer: ChannelBuffer ⇒
        val wrappedRequest = WebProxy.createWrappedRequest(connectHost, proxyHost)
        wrappedRequest.setHeader(ProxyRequestType.name, HTTPS.value)
        setContent(wrappedRequest, RequestData.toBuffer(RequestData(requestInfo.requestID, buffer)))
        wrappedRequest.setHeader("x-i", requestInfo.sequenceID.incrementAndGet())
        wrappedRequest
      case e ⇒ e
    }
    super.encode(ctx, channel, toBeSentMessage)
  }

}

class WebProxyResponseBufferEncoder extends OneToOneEncoder with LazyLogging {

  override def encode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = {
    logger.debug(s"[$channel] - receive data: ${Utils.formatMessage(msg)}")

    msg match {
      case buffer: ChannelBuffer if buffer.readableBytes() > 0 ⇒ writeBufferResponse(channel, buffer)
      case WebProxy.TickResponse                               ⇒ writeBufferResponse(channel, ChannelBuffers.EMPTY_BUFFER)
      case WebProxy.PrepareResponse ⇒
        logger.debug(s"[$channel] - Initialize chunked response, requestID: ${channel.getAttachment}")

        val timerTask: TimerTask = new TimerTask() {
          def run() {
            if (channel.isConnected)
              channel.write(WebProxy.TickResponse).addListener {
                writeFuture: ChannelFuture ⇒
                  logger.error(s"[${ctx.getChannel}] - Finished write tick response")
              }
            else
              DefaultTimerTaskManager.remove(channel.getAttachment.toString) foreach (_.cancel)
          }
        }
        DefaultTimerTaskManager.add(channel.getAttachment.toString, timerTask)
        timer.scheduleAtFixedRate(timerTask, 40000, 40000)

        val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.setChunked(true)
        response
      case WebProxy.CompleteRelayDataRequest ⇒ {
        val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, 0)
        logger.error(s">>>>>>>>>>>>>>>>$channel #### -\n ${Utils.formatMessage(response)}")
        response
      }
      case WebProxy.FinishResponse ⇒
        logger.debug(s"[$channel] - Finishing response.")
        DefaultTimerTaskManager.remove(channel.getAttachment.toString) foreach (_.cancel)
        new DefaultHttpChunkTrailer
      case _ ⇒ msg
    }

  }

  def writeBufferResponse(channel: Channel, buffer: ChannelBuffer): DefaultHttpChunk = {
    logger.debug(s"[$channel] - Writing response \n ${Utils.formatMessage(buffer)}")
    val encrypt = /*encryptor.encrypt(buffer) */ bufferToArray(buffer)
    val lengthBuffer = ChannelBuffers.dynamicBuffer(4)
    lengthBuffer.writeInt(encrypt.length)
    val wrappedBuffer: ChannelBuffer = ChannelBuffers.wrappedBuffer(lengthBuffer.array(), encrypt)
    new DefaultHttpChunk(wrappedBuffer)
  }
}

class NettyLoggingHandler extends LazyLogging with ChannelUpstreamHandler with ChannelDownstreamHandler {

  def handleUpstream(ctx: ChannelHandlerContext, e: ChannelEvent) {

    e match {
      case me: MessageEvent if me.getMessage.isInstanceOf[ChannelBuffer] ⇒
        logger.error(s">>>>>>>>>>>[${e.getChannel.getAttachment}] - Received: ${Utils.formatMessage(me.getMessage.asInstanceOf[ChannelBuffer])}")
      case _ ⇒
    }
    ctx.sendUpstream(e)
  }

  def handleDownstream(ctx: ChannelHandlerContext, e: ChannelEvent) {
    e match {
      case me: MessageEvent if me.getMessage.isInstanceOf[ChannelBuffer] ⇒
        logger.error(s"################Send: ${Utils.formatMessage(me.getMessage.asInstanceOf[ChannelBuffer])}")
      case _ ⇒
    }
    ctx.sendDownstream(e)
  }
}

class EncryptDataFrameDecoder extends FrameDecoder with LazyLogging {

  def decode(ctx: ChannelHandlerContext, channel: Channel, buffer: ChannelBuffer): AnyRef = {
    logger.debug(s"[$channel] - Receive data: ${Utils.formatMessage(buffer)}")
    def isConsistentPacket(buffer: ChannelBuffer) = {
      val length = buffer.getInt(buffer.readerIndex())
      buffer.readableBytes() >= 4 && buffer.readableBytes() >= (length + 4)
    }
    if (isConsistentPacket(buffer)) {
      val dataPacket = buffer.readBytes(buffer.readInt())
      ChannelBuffers.wrappedBuffer( /*encryptor.decrypt(dataPacket)*/ dataPacket)
    } else null

  }
}

/**
 * HTTP process flow:
 * 1. Send proxy request to WebProxyServer
 * 2. WebProxyServer initialize a chunked response and return response by continue chunk
 * 3. Request process completed when reach last chunk and close the channel
 * 4. Process completed.
 *
 * HTTPs process flow:
 * 1. Send proxy connect request to WebProxyServer
 * 2. WebProxyServer initialize a chunked response for this connect request, then keep this channel open
 * and return response data by continue chunk with this channel for other every request.
 * 3. Create new channel to relay the browser request data to WebProxyServer for continue request, WebProxyServer
 * return with unchunked response without content for this channel, we close it when get return.
 * 4. Close connect request's channel after WebProxyServer completed the connect request(with last chunk response)
 * 5. Process completed.
 *
 * Response data format: Data-packet-length(2 bytes) + Encrypt-Data
 *
 *
 * @param browserChannel
 */
class WebProxyResponseDecoder(browserChannel: Channel) extends OneToOneDecoder with LazyLogging {
  def decode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = {
    logger.debug(s"[${channel}] - Receive message\n ${Utils.formatMessage(msg)}")
    msg match {
      case response: HttpResponse if response.getStatus.getCode != 200 && response.getStatus.getCode != 503 ⇒ {
        logger.warn(s"Web proxy error,\n${IOUtils.toString(response.getContent.array(), UTF8.name())}}")
        throw new RuntimeException("WebProx Error:")
      }
      case response: HttpResponse if response.getStatus.getCode == 503 ⇒ {
        logger.debug(s"Web proxy timeout,\n${IOUtils.toString(response.getContent.array(), UTF8.name())}}")
        ChannelBuffers.EMPTY_BUFFER
      }
      case response: HttpResponse if !response.isChunked ⇒ { //For https data relay
        logger.info("Proxy request relay to remote server by WebProxy successful.")
        WebProxy.Close
      }
      case response: HttpResponse if response.isChunked ⇒ {
        logger.debug(s"[${channel}] - HTTPS request initialized..................")
        ChannelBuffers.EMPTY_BUFFER
      }
      case chunk: HttpChunk if !chunk.isLast ⇒ chunk.getContent
      case chunk: HttpChunk if chunk.isLast  ⇒ WebProxy.Close
      case unknownMessage                    ⇒ throw new RuntimeException(s"Received UnknownMessage: $unknownMessage")
    }
  }

}

