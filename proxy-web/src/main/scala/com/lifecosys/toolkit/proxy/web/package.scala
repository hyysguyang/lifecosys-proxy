package com.lifecosys.toolkit.proxy

import com.lifecosys.toolkit.proxy.web.netty.ChannelManager
import java.util.concurrent.{ Executor, Executors }
import org.jboss.netty.channel.socket.nio.{ NioClientSocketChannelFactory, NioWorker, NioWorkerPool }
import javax.servlet.http.HttpServletResponse

/**
 *
 *
 * @author Young Gu
 * @version 1.0 8/9/13 1:50 PM
 */
package object web {

  def writeErrorResponse(response: HttpServletResponse) {
    response.setHeader(ResponseCompleted.name, "true")
    response.setStatus(200)
    response.setContentType("application/octet-stream")
    response.setContentLength("HTTP/1.1 400 Can't establish connection\r\n\r\n".getBytes("UTF-8").length)
    response.getOutputStream.write("HTTP/1.1 400 Can't establish connection\r\n\r\n".getBytes("UTF-8"))
    response.getOutputStream.flush()
  }
}
