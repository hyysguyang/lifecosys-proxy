package com.lifecosys.toolkit.proxy

/**
 * Created with IntelliJ IDEA.
 * User: young
 * Date: 12/3/12
 * Time: 12:50 AM
 * To change this template use File | Settings | File Templates.
 */
object Launcher {
  def main(args: Array[String]) {
    ProxyServer(8080) start
  }
}


