package com.lifecosys.toolkit.ssl

import javax.net.ssl.SSLContext

/**
 *
 *
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 12/19/12 7:38 PM
 */
trait SSLManager {
  //  def keyStorePassword: String
  //
  //  def keyManagerKeyStoreInputStream: InputStream
  //
  //  def trustKeyStorePassword: String
  //
  //  def trustManagerKeyStoreInputStream: InputStream

  def getServerSSLContext: SSLContext

  def getProxyToServerSSLContext: SSLContext

}
