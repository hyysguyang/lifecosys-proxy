package com.lifecosys.toolkit.proxy

import javax.servlet.http.{ HttpServletResponse, HttpServletRequest, HttpServlet }
import org.jboss.netty.channel._
import org.jboss.netty.buffer._
import org.jboss.netty.channel.socket.nio.{ NioWorker, NioWorkerPool, NioClientSocketChannelFactory }
import java.util.concurrent.{ Executor, Executors }
import org.jboss.netty.handler.codec.http._
import org.apache.commons.io.IOUtils
import org.jboss.netty.logging.{ Slf4JLoggerFactory, InternalLoggerFactory }
import com.typesafe.scalalogging.slf4j.Logging
import org.apache.commons.lang3.StringUtils
import java.net.Socket
import java.nio.channels.ClosedChannelException
import java.nio.ByteBuffer
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.util.concurrent.atomic.AtomicBoolean

/**
 *
 *
 * @author Young Gu
 * @version 1.0 6/21/13 10:28 AM
 */
trait ChannelManager {
  private[this] val channels = scala.collection.mutable.Map[ChannelKey, Channel]()
  val requests = new scala.collection.mutable.SynchronizedQueue[ChannelBuffer]()

  def get(channelKey: ChannelKey) = channels.get(channelKey)

  def add(channelKey: ChannelKey, channel: Channel) = channels += channelKey -> channel
}

class HttpsOutboundHandler(var servletResponse: HttpServletResponse = null) extends SimpleChannelUpstreamHandler with Logging {

  var data: Option[DataHolder] = None

  //  val finishByte=Array[Byte](0x14, ox03,0x01,0x00 01 01)
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.debug(s"############${e.getChannel} receive message###############\n ${Utils.formatMessage(e.getMessage)}")
    val buffer: ChannelBuffer = e.getMessage.asInstanceOf[ChannelBuffer]

    data match {
      case None ⇒ {
        var dataLength: Int = 0
        var sslRecord = ByteBuffer.allocateDirect(5)
        buffer.readBytes(sslRecord)
        sslRecord.flip()

        dataLength = sslRecord.remaining()
        //Store sslRecord first
        var sentBuffer: ChannelBuffer = ChannelBuffers.copiedBuffer(sslRecord)
        //          if(sslRecord.get()==0x14 && buffer.readableBytes()>0){//Server Done and Verify data record.
        //          val serverDoneLength=sslRecord.getShort(3)
        //            val serverDoneData=new Array[Byte](serverDoneLength)
        //            buffer.readBytes(serverDoneData)
        //            sentBuffer=ChannelBuffers.wrappedBuffer(sentBuffer,ChannelBuffers.copiedBuffer(serverDoneData))
        //            dataLength+=serverDoneLength
        //
        //            sslRecord.position(0)
        //            buffer.readBytes(sslRecord)
        //            sslRecord.flip()
        //            dataLength += sslRecord.remaining()
        //            sentBuffer=ChannelBuffers.wrappedBuffer(sentBuffer,ChannelBuffers.copiedBuffer(sslRecord))
        //          }

        //The continuous data length
        dataLength += sslRecord.getShort(3)

        sentBuffer = ChannelBuffers.wrappedBuffer(sentBuffer, buffer)

        synchronized(data = Some(DataHolder(dataLength, sentBuffer)))
      }
      case Some(holder) ⇒ holder.buffer = ChannelBuffers.wrappedBuffer(holder.buffer, buffer)
    }
    data foreach {
      send ⇒
        if (send.ready) {
          logger.debug(s"Write response buffer\n${Utils.formatMessage(ChannelBuffers.copiedBuffer(send.buffer))}")
          //        response.setStatus(HttpServletResponse.SC_OK)
          //        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
          //        response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
          //        response.getOutputStream.flush
          servletResponse.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
          servletResponse.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
          servletResponse.setContentLength(send.contentLength)
          servletResponse.setHeader(ResponseCompleted.name, "true")
          send.buffer.readBytes(servletResponse.getOutputStream, send.contentLength)
          servletResponse.getOutputStream.flush
          synchronized(data = None)

          //        response.setStatus(200)
          //        response.setContentType("application/octet-stream")
          //        response.setContentLength(Utils.connectProxyResponse.getBytes("UTF-8").length)
          //        response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
          //        response.getOutputStream.flush()

          //        response.setContentLength(0)
          //        response.getOutputStream.write(Array[Byte]())
          //        response.getOutputStream.flush

          //        response.setStatus(200)
          //        response.setContentType("application/octet-stream")
          //        response.setContentLength(0)
          //        response.getOutputStream.flush()
        }

    }

    //  data match {
    //    case None => {
    //      var dataLength: Int =0
    //      var sslRecord = ByteBuffer.allocateDirect(5)
    //      buffer.readBytes(sslRecord)
    //      sslRecord.flip()
    //      dataLength= sslRecord.remaining()
    //      //Store sslRecord first
    //      var sentBuffer: ChannelBuffer=ChannelBuffers.copiedBuffer(sslRecord)
    //      if(sslRecord.get()==0x14 && buffer.readableBytes()>0){//Server Done and Verify data record.
    //      val serverDoneLength=sslRecord.getShort(3)
    //        val serverDoneData=new Array[Byte](serverDoneLength)
    //        buffer.readBytes(serverDoneData)
    //        sentBuffer=ChannelBuffers.wrappedBuffer(sentBuffer,ChannelBuffers.copiedBuffer(serverDoneData))
    //        dataLength+=serverDoneLength
    //
    //        sslRecord.position(0)
    //        buffer.readBytes(sslRecord)
    //        sslRecord.flip()
    //        dataLength += sslRecord.remaining()
    //        sentBuffer=ChannelBuffers.wrappedBuffer(sentBuffer,ChannelBuffers.copiedBuffer(sslRecord))
    //      }
    //
    //      //The continuous data length
    //      dataLength += sslRecord.getShort(3)
    //
    //      sentBuffer=ChannelBuffers.wrappedBuffer(sentBuffer, buffer)
    //
    //      synchronized(data=Some(DataHolder(dataLength,sentBuffer)))
    //    }
    //    case Some(holder) =>holder.buffer=ChannelBuffers.wrappedBuffer(holder.buffer,buffer)
    //  }
    //  data foreach{
    //    send => if  (send.ready) {
    //      logger.debug(s"Write response buffer\n${Utils.formatMessage(ChannelBuffers.copiedBuffer(send.buffer))}")
    //      //        response.setStatus(HttpServletResponse.SC_OK)
    //      //        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    //      //        response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
    //      //        response.getOutputStream.flush
    //      response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    //      response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
    //      response.setContentLength(send.length)
    //      response.setHeader(ResponseCompleted.name, "true")
    //      send.buffer.readBytes(response.getOutputStream, send.length)
    //      response.getOutputStream.flush
    //      synchronized(data=None)
    //
    //      //        response.setStatus(200)
    //      //        response.setContentType("application/octet-stream")
    //      //        response.setContentLength(Utils.connectProxyResponse.getBytes("UTF-8").length)
    //      //        response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
    //      //        response.getOutputStream.flush()
    //
    //
    //
    //      //        response.setContentLength(0)
    //      //        response.getOutputStream.write(Array[Byte]())
    //      //        response.getOutputStream.flush
    //
    //      //        response.setStatus(200)
    //      //        response.setContentType("application/octet-stream")
    //      //        response.setContentLength(0)
    //      //        response.getOutputStream.flush()
    //    }
    //
    //  }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn(s"Got exception on ${ctx.getChannel}", e.getCause)
    e.getChannel.close()
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.warn("###################Got closed event on : %s".format(e.getChannel))
  }
}

class HttpOutboundHandler(var servletResponse: HttpServletResponse) extends SimpleChannelUpstreamHandler with Logging {
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    logger.debug(s"############${e.getChannel} receive message###############\n${Utils.formatMessage(e.getMessage)}")
    def encodeHttpResponse(response: Any): ChannelBuffer = {
      val encode = classOf[HttpResponseEncoder].getSuperclass.getDeclaredMethods.filter(_.getName == "encode")(0)
      encode.setAccessible(true)
      encode.invoke(new HttpResponseEncoder(), null, ctx.getChannel, e.getMessage).asInstanceOf[ChannelBuffer]
    }
    e.getMessage match {
      case response: HttpResponse if !response.isChunked ⇒ {
        servletResponse.setHeader(ResponseCompleted.name, "true")
        val responseBuffer = encodeHttpResponse(response)
        servletResponse.setContentLength(responseBuffer.readableBytes())
        responseBuffer.readBytes(servletResponse.getOutputStream, responseBuffer.readableBytes)
        servletResponse.getOutputStream.flush
        Utils.closeChannel(e.getChannel)
      }
      case response: HttpResponse if response.isChunked ⇒ {
        servletResponse.setHeader(ResponseCompleted.name, "false")
        val responseBuffer = encodeHttpResponse(response)
        responseBuffer.readBytes(servletResponse.getOutputStream, responseBuffer.readableBytes)
        servletResponse.getOutputStream.flush
      }
      case chunk: HttpChunk if !chunk.isLast ⇒ {
        servletResponse.setHeader(ResponseCompleted.name, "false")
        val responseBuffer = encodeHttpResponse(chunk)
        responseBuffer.readBytes(servletResponse.getOutputStream, responseBuffer.readableBytes)
        servletResponse.getOutputStream.flush
      }
      case chunk: HttpChunk if chunk.isLast ⇒ {
        servletResponse.setHeader(ResponseCompleted.name, "true")
        servletResponse.setHeader("isLastChunk", "true")
        val responseBuffer = encodeHttpResponse(chunk)
        servletResponse.setContentLength(responseBuffer.readableBytes())
        responseBuffer.readBytes(servletResponse.getOutputStream, responseBuffer.readableBytes)
        servletResponse.getOutputStream.flush
        Utils.closeChannel(e.getChannel)
      }
      case _ ⇒
    }

    //    response.setHeader(ResponseCompleted.name, "true")
    //    response.setHeader(ResponseCompleted.name, "true")
    //    response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, "")
    //    response.setContentType("application/octet-stream")
    //    response.setContentLength(1)
    //    response.getOutputStream.write(0x00)
    //    response.getOutputStream.flush()
    //    response.getOutputStream.write(0)
    //    response.getOutputStream.write(HttpConstants.CR)
    //    response.getOutputStream.write(HttpConstants.LF)

    //    response.setHeader(ResponseCompleted.name, "true")
    //    response.setContentType("application/octet-stream")
    //    response.setContentLength("chunked".getBytes().length)
    //    response.getOutputStream.write("chunked".getBytes())

    //    response.setStatus(200)
    //    response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
    //    response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED)
    //    response.setHeader(HttpHeaders.Names.TRAILER, "checksum")
    //    response.setHeader("checksum", "checksum")
    //    response.setContentLength(0)

    //    response.setContentType("text/plain")
    //    response.setContentLength("HTTP/1.1 400 Can't establish connection\r\n\r\n".getBytes("UTF-8").length)
    //    response.getOutputStream.write("HTTP/1.1 400 Can't establish connection\r\n\r\n".getBytes("UTF-8"))

    //    output.writeBytes("HTTP/1.1 200 OK");
    //    addCRLR(output);
    //    output.writeBytes("Content-type: text/plain");
    //    addCRLR(output);
    //    output.writeBytes("Transfer-encoding: chunked");
    //    addCRLR(output);
    //    output.writeBytes("Trailer: checksum");

  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    logger.debug("Got closed event on : %s".format(e.getChannel))
    //    response.setStatus(200)
    //    response.setHeader(ResponseCompleted.name, "true")
    //    response.setContentType("application/octet-stream")
    //    response.setContentLength(0)
    //    response.getOutputStream.flush()
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    logger.warn("Caught exception on : %s".format(e.getChannel), e.getCause)
    e.getCause match {
      case closeException: ClosedChannelException ⇒ //Just ignore it
      case exception                              ⇒ Utils.closeChannel(e.getChannel)
    }
  }
}

object ProxyServlet {
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  Utils.installJCEPolicy
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  Security.addProvider(new BouncyCastleProvider)
}

class ProxyServlet extends HttpServlet with Logging {
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  Utils.installJCEPolicy
  InternalLoggerFactory.setDefaultFactory(new Slf4JLoggerFactory)
  Security.addProvider(new BouncyCastleProvider)
  val channelManager = new ChannelManager {}
  val executor = Executors.newCachedThreadPool()

  val pool: NioWorkerPool = new NioWorkerPool(executor, 100) {
    override def newWorker(executor: Executor): NioWorker = {
      new NioWorker(executor, null)
    }
  }
  val clientSocketChannelFactory = new NioClientSocketChannelFactory(executor, 1, pool)

  //
  //  override def init(servletConfig: ServletConfig) {
  //    val config = ConfigFactory.load()
  //    val proxyConfig=new ProgrammaticCertificationProxyConfig(Some(config))
  //    servletConfig.getServletContext.setAttribute("proxyConfig",proxyConfig)
  //  }

  override def service(request: HttpServletRequest, response: HttpServletResponse) {
    if (StringUtils.isEmpty(request.getRequestedSessionId) && request.getSession(false) == null) {
      request.getSession(true)
      logger.debug(s"Created session: ${request.getSession.getId} for request: ${request}")
    }

    require(StringUtils.isNotEmpty(request.getSession.getId), "Session have not been created, server error.")

    val compressedData: Array[Byte] = IOUtils.toByteArray(request.getInputStream)
    val data: Array[Byte] = Utils.inflate(compressedData)

    val encryptedProxyRequest = data

    //    val encryptedProxyRequestChannelBuffer = ChannelBuffers.dynamicBuffer(512)
    //    val bufferStream = new ChannelBufferOutputStream(encryptedProxyRequestChannelBuffer)
    //    IOUtils.copy(request.getInputStream, bufferStream)
    //    IOUtils.closeQuietly(bufferStream)
    logger.debug(s"############Process payload ###############\n${Utils.hexDumpToString(encryptedProxyRequest)}")
    Utils.installJCEPolicy
    val proxyRequestChannelBuffer = ChannelBuffers.wrappedBuffer(encryptor.decrypt(encryptedProxyRequest))
    logger.debug(s"Decrypted proxy request:${Utils.formatMessage(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer))}")

    val proxyHost = Host(request.getHeader(ProxyHostHeader.name))
    val channelKey: ChannelKey = ChannelKey(request.getSession.getId, proxyHost)

    //User HTTP for unset flag just make less data bytes.
    def requestType = try {
      RequestType(request.getHeader(ProxyRequestType.name).toByte)
    } catch {
      case _ ⇒ HTTP
    }

    requestType match {
      case HTTPS ⇒ httpsProcessor.processHttps(request, response, proxyHost, channelKey, proxyRequestChannelBuffer)
      case _     ⇒ new HttpRequestProcessor(response, proxyHost, channelKey, proxyRequestChannelBuffer).process
    }
  }

  val httpsProcessor = new MultiTaskHttpsRequestProcessor()
  System.setProperty("javax.net.debug", "all")

  private[this] val sockets = scala.collection.mutable.Map[ChannelKey, Socket]()

  trait RequestProcessor {
    def process(channelKey: ChannelKey, proxyRequestChannelBuffer: ChannelBuffer)(implicit request: HttpServletRequest, response: HttpServletResponse)
  }

  case class Message(request: HttpServletRequest, response: HttpServletResponse, proxyRequestChannelBuffer: ChannelBuffer)

  abstract class Task(channelKey: ChannelKey, handler: HttpsOutboundHandler) {
    val channel = {
      logger.debug(s"Connecting to: ${channelKey.proxyHost}")
      val clientBootstrap = newClientBootstrap
      clientBootstrap.setFactory(clientSocketChannelFactory)
      clientBootstrap.getPipeline.addLast("handler", handler)
      val channelFuture = clientBootstrap.connect(channelKey.proxyHost.socketAddress).awaitUninterruptibly()
      val channel: Channel = channelFuture.getChannel
      //TODO:Update buffer size.
      channel.getConfig.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(1024 * 1024))
      logger.debug(s"Connect to completed: ${channelKey.proxyHost}, status: ${channel.isConnected}")
      channel
    }
    def submit(message: Message): Unit
  }

  abstract class BaseHttpsRequestProcessor extends RequestProcessor {
    val tasks = scala.collection.mutable.Map[ChannelKey, Task]()
    def releaseTask(channelKey: ChannelKey)

  }

  class SimpleHttpsRequestProcessor extends BaseHttpsRequestProcessor {

    def processHttps(request: HttpServletRequest, response: HttpServletResponse, proxyHost: Host, channelKey: ChannelKey, proxyRequestChannelBuffer: ChannelBuffer) {

      channelManager.get(channelKey) match {
        case Some(channel) ⇒ {
          channel.getPipeline.get(classOf[HttpsOutboundHandler]).servletResponse = response
          if (channel.isConnected) {

            response.setStatus(HttpServletResponse.SC_OK)
            response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
            response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
            // Initiate chunked encoding by flushing the headers.
            response.getOutputStream.flush()

            channel.write(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer))
          } else {
            //todo: error process
            channel.close()
            writeErrorResponse(response)
          }

        }
        case None ⇒ {
          val clientBootstrap = newClientBootstrap
          clientBootstrap.setFactory(clientSocketChannelFactory)
          clientBootstrap.getPipeline.addLast("handler", new HttpsOutboundHandler(response))
          val channelFuture = clientBootstrap.connect(proxyHost.socketAddress).awaitUninterruptibly()
          val channel: Channel = channelFuture.getChannel
          //TODO:Update buffer size.
          channel.getConfig.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(1024 * 1024))
          if (channelFuture.isSuccess() && channel.isConnected) {
            channelManager.add(channelKey, channel)
            response.setStatus(200)
            response.setContentType("application/octet-stream")
            response.setHeader("response-completed", "true")
            response.setContentLength(Utils.connectProxyResponse.getBytes("UTF-8").length)
            response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
            response.getOutputStream.flush()
            return
          } else {
            //todo: error process
            channelFuture.getChannel.close()
            writeErrorResponse(response)
            return
          }

        }
      }

      //      if (HttpMethod.CONNECT.getName == request.getHeader("proxyRequestMethod")) {
      //        val clientBootstrap = newClientBootstrap
      //        clientBootstrap.setFactory(clientSocketChannelFactory)
      //        clientBootstrap.getPipeline.addLast("handler", new HttpsOutboundHandler(response))
      //        val channelFuture = clientBootstrap.connect(proxyHost.socketAddress).awaitUninterruptibly()
      //        val channel: Channel = channelFuture.getChannel
      //        //TODO:Update buffer size.
      //        channel.getConfig.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(1024 * 1024))
      //        if (channelFuture.isSuccess() && channel.isConnected) {
      //          channelManager.add(channelKey, channel)
      //          response.setStatus(200)
      //          response.setContentType("application/octet-stream")
      //          response.setHeader("response-completed", "true")
      //          response.setContentLength(Utils.connectProxyResponse.getBytes("UTF-8").length)
      //          response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
      //          response.getOutputStream.flush()
      //          return
      //        } else {
      //          //todo: error process
      //          channelFuture.getChannel.close()
      //          writeErrorResponse(response)
      //          return
      //        }
      //
      //      } else if ("HTTPS-DATA-TRANSFER" == request.getHeader("proxyRequestMethod")) {
      //
      //        val channel = channelManager.get(channelKey).get
      //        channel.getPipeline.get(classOf[HttpsOutboundHandler]).servletResponse = response
      //        if (channel.isConnected) {
      //
      //          response.setStatus(HttpServletResponse.SC_OK)
      //          response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
      //          response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
      //          // Initiate chunked encoding by flushing the headers.
      //          response.getOutputStream.flush()
      //
      //          channel.write(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer))
      //        } else {
      //          //todo: error process
      //          channel.close()
      //          writeErrorResponse(response)
      //        }
      //
      //      }

    }

    def process(channelKey: ChannelKey, proxyRequestChannelBuffer: ChannelBuffer)(implicit request: HttpServletRequest, response: HttpServletResponse) {}

    def releaseTask(channelKey: ChannelKey) {}
  }

  class MultiTaskHttpsRequestProcessor extends BaseHttpsRequestProcessor {
    def processHttps(request: HttpServletRequest, response: HttpServletResponse, proxyHost: Host, channelKey: ChannelKey, proxyRequestChannelBuffer: ChannelBuffer) {
      class QueuedTask(channelKey: ChannelKey, handler: HttpsOutboundHandler) extends Task(channelKey, handler) {
        val isRunning = new AtomicBoolean()
        val messages = new scala.collection.mutable.SynchronizedQueue[Message]()

        def submit(message: Message) = channelConnected {
          response.setStatus(HttpServletResponse.SC_OK)
          response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
          response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.BINARY)
          // Initiate chunked encoding by flushing the headers.
          response.getOutputStream.flush()
          process(message)
        }

        def process(message: Message) {
          logger.debug(s"Enqueue HTTPS request to task: $message")
          messages += message
          if (!isRunning.get) {
            isRunning.set(true)
            executor.submit(new Runnable {
              def run = {
                while (!messages.isEmpty) {
                  val message = messages.dequeue()
                  logger.debug(s"Processing HTTPS request: $message, ${messages.size} pending...")
                  handler.servletResponse = message.response
                  channel.write(ChannelBuffers.copiedBuffer(message.proxyRequestChannelBuffer))
                }
                isRunning.set(false)
              }
            })
          }
        }

        def channelConnected(processor: ⇒ Unit) = {
          if (channel.isConnected) processor
          else {
            channel.close()
            releaseTask(channelKey)
            writeErrorResponse(response)
          }
        }

      }

      def handler = new HttpsOutboundHandler() {
        override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
          releaseTask(channelKey)
        }
      }

      def connect {
        val task = new QueuedTask(channelKey, handler)
        tasks += channelKey -> task
        task channelConnected {
          response.setStatus(200)
          response.setContentType("application/octet-stream")
          response.setHeader(ResponseCompleted.name, "true")
          response.setContentLength(Utils.connectProxyResponse.getBytes("UTF-8").length)
          response.getOutputStream.write(Utils.connectProxyResponse.getBytes("UTF-8"))
          response.getOutputStream.flush()
        }
      }
      tasks.get(channelKey) match {
        case Some(task) ⇒ task.submit(Message(request, response, proxyRequestChannelBuffer))
        case None       ⇒ connect
      }
    }

    def releaseTask(channelKey: ChannelKey) {
      tasks -= channelKey
    }

    def process(channelKey: ChannelKey, proxyRequestChannelBuffer: ChannelBuffer)(implicit request: HttpServletRequest, response: HttpServletResponse) {}
  }

  class HttpRequestProcessor(response: HttpServletResponse, proxyHost: Host, channelKey: ChannelKey, proxyRequestChannelBuffer: ChannelBuffer) {
    val clientBootstrap = newClientBootstrap
    clientBootstrap.setFactory(clientSocketChannelFactory)
    clientBootstrap.getPipeline.addLast("decoder", new HttpResponseDecoder(8192 * 2, 8192 * 4, 8192 * 4))
    clientBootstrap.getPipeline.addLast("handler", new HttpOutboundHandler(response))
    val channelFuture = clientBootstrap.connect(proxyHost.socketAddress).awaitUninterruptibly()

    def process {
      if (channelFuture.isSuccess() && channelFuture.getChannel.isConnected) {
        channelManager.add(channelKey, channelFuture.getChannel)
      } else {
        channelFuture.getChannel.close()
        writeErrorResponse(response)
        return
      }
      val channel = channelManager.get(channelKey).get
      channel.getPipeline.get(classOf[HttpOutboundHandler]).servletResponse = response
      if (channel.isConnected) {
        response.setStatus(HttpServletResponse.SC_OK)
        response.setHeader(ResponseCompleted.name, "false")
        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
        response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED)
        //        response.setHeader(HttpHeaders.Names.TRAILER, "checksum")
        // Initiate chunked encoding by flushing the headers.
        response.getOutputStream.flush()

        //
        //        response.setStatus(200)
        //        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "application/octet-stream")
        //        response.setHeader(HttpHeaders.Names.CONTENT_TRANSFER_ENCODING, HttpHeaders.Values.CHUNKED)
        //        response.setHeader("checksum", "checksum")
        //        response.setContentLength(0)

        channel.write(ChannelBuffers.copiedBuffer(proxyRequestChannelBuffer))
      } else {
        channel.close()
        writeErrorResponse(response)
      }
    }
  }

  def writeErrorResponse(response: HttpServletResponse) {
    response.setHeader(ResponseCompleted.name, "true")
    response.setStatus(200)
    response.setContentType("application/octet-stream")
    response.setContentLength("HTTP/1.1 400 Can't establish connection\r\n\r\n".getBytes("UTF-8").length)
    response.getOutputStream.write("HTTP/1.1 400 Can't establish connection\r\n\r\n".getBytes("UTF-8"))
    response.getOutputStream.flush()
  }

}
