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

import org.jboss.netty.handler.codec.http._
import org.jboss.netty.channel._
import org.jboss.netty.buffer.{ ChannelBuffer, ChannelBuffers }
import org.jboss.netty.handler.codec.compression.{ ZlibEncoder, ZlibDecoder }
import org.jboss.netty.handler.codec.oneone.{ OneToOneDecoder, OneToOneEncoder }
import org.littleshoot.proxy.ProxyUtils
import org.apache.commons.io.IOUtils
import com.typesafe.scalalogging.slf4j.Logging
import scala.util.Try
import org.apache.commons.lang3.StringUtils

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
  def jsessionidCookie(channel: Channel) = channel.getAttachment match {
    case Some(jsessionid) if jsessionid.isInstanceOf[Cookie] ⇒ {
      val encoder = new CookieEncoder(false)
      encoder.addCookie(jsessionid.asInstanceOf[Cookie])
      Some(encoder.encode())
    }
    case _ ⇒ None
  }

  def createWrappedRequest(connectHost: ConnectHost, proxyHost: Host, jsessionidCookie: Option[String]) = {
    val wrappedRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/proxy")
    wrappedRequest.setHeader(HttpHeaders.Names.HOST, connectHost.host.host)
    wrappedRequest.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE)
    wrappedRequest.addHeader(HttpHeaders.Names.ACCEPT, "application/octet-stream")
    wrappedRequest.addHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    wrappedRequest.setHeader(HttpHeaders.Names.USER_AGENT, "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:22.0) Gecko/20100101 Firefox/22.0")
    wrappedRequest.setHeader(ProxyHostHeader.name, proxyHost.toString)
    jsessionidCookie.foreach(wrappedRequest.setHeader(HttpHeaders.Names.COOKIE, _))
    wrappedRequest
  }
}

class WebProxyHttpRequestEncoder(connectHost: ConnectHost, proxyHost: Host)(implicit browserChannelContext: ChannelHandlerContext)
    extends HttpRequestEncoder with Logging {
  val browserChannel = browserChannelContext.getChannel
  val jsessionidCookie = WebProxy.jsessionidCookie(browserChannel)
  override def encode(ctx: ChannelHandlerContext, channel: Channel, msg: Any): AnyRef = {

    def setContent(wrappedRequest: DefaultHttpRequest, content: ChannelBuffer) = {
      logger.debug(s"Proxy request:\n ${Utils.formatMessage(content)}")
      //We may get CompositeChannelBuffer,such as for HttpRequest with content.
      val data = Try(content.array()).getOrElse(content.toByteBuffer.array())
      val encrypt: Array[Byte] = encryptor.encrypt(data)
      val compressedData = Utils.deflate(encrypt)
      val encryptedBuffer = ChannelBuffers.wrappedBuffer(compressedData)
      wrappedRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, encryptedBuffer.readableBytes().toString)
      logger.debug(s"Proxy encryptedBuffer request:\n ${Utils.hexDumpToString(encryptedBuffer.array())}")
      wrappedRequest.setContent(encryptedBuffer)
    }
    val toBeSentMessage = msg match {
      case request: HttpRequest ⇒ {
        val encodedProxyRequest = super.encode(ctx, channel, ProxyUtils.copyHttpRequest(request, false)).asInstanceOf[ChannelBuffer]
        val wrappedRequest = WebProxy.createWrappedRequest(connectHost, proxyHost, jsessionidCookie)
        setContent(wrappedRequest, encodedProxyRequest)
        wrappedRequest
      }
      case buffer: ChannelBuffer if buffer.readableBytes() == 0 ⇒ buffer //Process for close flush buffer.
      case buffer: ChannelBuffer ⇒
        val wrappedRequest = WebProxy.createWrappedRequest(connectHost, proxyHost, jsessionidCookie)
        wrappedRequest.setHeader(ProxyRequestType.name, HTTPS.value)
        setContent(wrappedRequest, buffer)
        wrappedRequest
      case e ⇒ e
    }
    super.encode(ctx, channel, toBeSentMessage)
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
 *
 * @param browserChannel
 */
class WebProxyResponseDecoder(browserChannel: Channel) extends OneToOneDecoder with Logging {
  def decode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef) = {
    logger.debug(s"[${channel}] - Receive message\n ${Utils.formatMessage(msg)}")
    msg match {
      case response: HttpResponse if response.getStatus.getCode != 200 ⇒ {
        logger.warn(s"Web proxy error,\n${IOUtils.toString(response.getContent.array(), Utils.UTF8.name())}}")
        throw new RuntimeException("WebProx Error:")
      }
      case response: HttpResponse if !response.isChunked ⇒ { //For https data relay
        logger.debug("Proxy request relay to remote server by WebProxy successful.")
        channel.close()
      }
      case response: HttpResponse if response.isChunked ⇒ {
        import scala.collection.JavaConverters._
        val setCookie = response.getHeader(HttpHeaders.Names.SET_COOKIE)
        if (StringUtils.isNotEmpty(setCookie) && browserChannel.getAttachment == null) {
          val jsessionid = new CookieDecoder().decode(setCookie).asScala.filter(_.getName == "JSESSIONID").headOption
          browserChannel.setAttachment(jsessionid)
        }
        ChannelBuffers.EMPTY_BUFFER
      }
      case chunk: HttpChunk if !chunk.isLast ⇒ chunk.getContent
      case chunk: HttpChunk if chunk.isLast  ⇒ channel.close()
      case unknownMessage                    ⇒ throw new RuntimeException(s"Received UnknownMessage: $unknownMessage")
    }
  }

}

