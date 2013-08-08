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
import org.jboss.netty.handler.ssl.SslHandler
import org.jboss.netty.handler.timeout.{ IdleStateEvent, IdleStateAwareChannelHandler, IdleStateHandler }
import org.jboss.netty.buffer.{ ChannelBufferInputStream, ChannelBuffer, ChannelBuffers }
import org.jboss.netty.handler.codec.compression.{ ZlibEncoder, ZlibDecoder }
import org.jboss.netty.handler.codec.serialization.{ ClassResolvers, ObjectDecoder, ObjectEncoder }
import org.jboss.netty.handler.codec.oneone.{ OneToOneDecoder, OneToOneEncoder }
import org.littleshoot.proxy.ProxyUtils
import org.apache.commons.io.IOUtils
import java.nio.channels.ClosedChannelException
import com.typesafe.scalalogging.slf4j.Logging
import java.util.zip.Deflater

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

class WebProxyHttpRequestEncoder(connectHost: ConnectHost, proxyHost: Host)(implicit browserChannelContext: ChannelHandlerContext)
    extends HttpRequestEncoder with Logging {
  val browserChannel = browserChannelContext.getChannel
  override def encode(ctx: ChannelHandlerContext, channel: Channel, msg: Any): AnyRef = {

    def setContent(wrappedRequest: DefaultHttpRequest, content: ChannelBuffer) = {
      logger.debug(s"Proxy request:\n ${Utils.formatMessage(ChannelBuffers.copiedBuffer(content))}")
      val encrypt: Array[Byte] = encryptor.encrypt(content.array())
      val compressedData = Utils.deflate(encrypt)
      val encryptedBuffer = ChannelBuffers.wrappedBuffer(compressedData)
      wrappedRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, encryptedBuffer.readableBytes().toString)
      wrappedRequest.setContent(encryptedBuffer)
    }
    val toBeSentMessage = msg match {
      case request: HttpRequest ⇒ { //TODO:Maybe we can remove this since we should only receive channel buffer
        val encodedProxyRequest = super.encode(ctx, channel, ProxyUtils.copyHttpRequest(request, false)).asInstanceOf[ChannelBuffer]
        logger.debug("Encoded proxy request:\n" + IOUtils.toString(new ChannelBufferInputStream(ChannelBuffers.copiedBuffer(encodedProxyRequest))))
        val wrappedRequest = createWrappedRequest
        //        wrappedRequest.setHeader(ProxyRequestType.name, HTTP.value)
        setContent(wrappedRequest, encodedProxyRequest)
        wrappedRequest
      }
      case buffer: ChannelBuffer if buffer.readableBytes() == 0 ⇒ buffer //Process for close flush buffer.
      case buffer: ChannelBuffer ⇒
        val wrappedRequest = createWrappedRequest
        wrappedRequest.setHeader(ProxyRequestType.name, HTTPS.value)
        //        val encrypt: Array[Byte] = encryptor.encrypt(buffer.array())
        //        val compressedData = Utils.deflate(encrypt, Deflater.BEST_COMPRESSION)
        //        val encryptedBuffer = ChannelBuffers.wrappedBuffer(compressedData)
        //        wrappedRequest.setHeader(HttpHeaders.Names.CONTENT_LENGTH, encryptedBuffer.readableBytes().toString)
        //        wrappedRequest.setContent(encryptedBuffer)
        //        wrappedRequest
        setContent(wrappedRequest, buffer)
        wrappedRequest
      case e ⇒ e
    }
    super.encode(ctx, channel, toBeSentMessage)
  }

  def createWrappedRequest = {

    val wrappedRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, "/proxy/proxy")
    wrappedRequest.setHeader(HttpHeaders.Names.HOST, connectHost.host.host)
    wrappedRequest.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE)
    wrappedRequest.addHeader(HttpHeaders.Names.ACCEPT, "application/octet-stream")
    wrappedRequest.addHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    wrappedRequest.setHeader(HttpHeaders.Names.USER_AGENT, "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:22.0) Gecko/20100101 Firefox/22.0")
    //        wrappedRequest.setHeader(HttpHeaders.Names.TE, "trailers")
    //    browserChannel.getAttachment match {
    //      case state: HttpsState ⇒ state.sessionId match {
    //        case Some(jsessionid) if jsessionid.isInstanceOf[Cookie] ⇒ {
    //          val encoder = new CookieEncoder(false)
    //          encoder.addCookie(jsessionid.asInstanceOf[Cookie])
    //          wrappedRequest.setHeader(HttpHeaders.Names.COOKIE, encoder.encode())
    //        }
    //        case _ ⇒
    //      }
    //      case _ ⇒
    //    }
    //

    browserChannel.getAttachment match {
      case Some(jsessionid) ⇒ {
        val encoder = new CookieEncoder(false)
        encoder.addCookie(jsessionid.asInstanceOf[Cookie])
        wrappedRequest.setHeader(HttpHeaders.Names.COOKIE, encoder.encode())
      }
      case _ ⇒
    }
    wrappedRequest.setHeader(ProxyHostHeader.name, proxyHost.toString)
    wrappedRequest
  }
}

