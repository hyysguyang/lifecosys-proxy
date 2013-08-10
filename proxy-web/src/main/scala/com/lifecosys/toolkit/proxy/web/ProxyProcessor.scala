package com.lifecosys.toolkit.proxy.web

import javax.servlet.http.{ HttpServletResponse, HttpServletRequest }

/**
 *
 *
 * @author Young Gu
 * @version 1.0 8/9/13 1:25 PM
 */
trait ProxyProcessor {
  def process(proxyRequestBuffer: Array[Byte])(implicit request: HttpServletRequest, response: HttpServletResponse)
}