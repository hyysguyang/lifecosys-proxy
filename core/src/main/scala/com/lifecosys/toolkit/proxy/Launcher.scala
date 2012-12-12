/*
 * ===Begin Copyright Notice===
 *
 * NOTICE
 *
 * THIS SOFTWARE IS THE PROPERTY OF AND CONTAINS CONFIDENTIAL INFORMATION OF
 * LIFECOSYS AND/OR ITS AFFILIATES OR SUBSIDIARIES AND SHALL NOT BE DISCLOSED
 * WITHOUT PRIOR WRITTEN PERMISSION. LICENSED CUSTOMERS MAY COPY AND ADAPT
 * THIS SOFTWARE FOR THEIR OWN USE IN ACCORDANCE WITH THE TERMS OF THEIR
 * SOFTWARE LICENSE AGREEMENT. ALL OTHER RIGHTS RESERVED.
 *
 * (c) COPYRIGHT 2013 LIFECOCYS. ALL RIGHTS RESERVED. THE WORD AND DESIGN
 * MARKS SET FORTH HEREIN ARE TRADEMARKS AND/OR REGISTERED TRADEMARKS OF
 * LIFECOSYS AND/OR ITS AFFILIATES AND SUBSIDIARIES. ALL RIGHTS RESERVED.
 * ALL LIFECOSYS TRADEMARKS LISTED HEREIN ARE THE PROPERTY OF THEIR RESPECTIVE
 * OWNERS.
 *
 * ===End Copyright Notice===
 */

package com.lifecosys.toolkit.proxy


/**
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 12/3/12 12:50 AM
 */
object Launcher {
  def main(args: Array[String]) {
    ProxyServer() start


    //    val dl = new scala.collection.mutable.MutableList[String]()
    //    val dlb = new scala.collection.mutable.MutableList[Byte]()
    //    val fin = new FileInputStream("/Develop/Project/office/lifecosys-toolkit/core/src/conf/lifecosys-proxy-server-keystore.jks");
    //    var ch: Int = -1
    //    do {
    //      ch = fin.read();
    //      dl.+=("0x%02X ".format(ch))
    //      dlb.+=(ch.asInstanceOf[Byte])
    //    } while (ch != -1);
    //
    //    dlb.+=(-1.asInstanceOf[Byte])
    //    fin.close();
    //
    //    println(dl.length)
    //    println(dlb.length)
    //    println(dlb.mkString(","))


    //    val array: Array[Byte] = IOUtils.toByteArray(new FileInputStream("/Develop/Project/office/lifecosys-toolkit/core/src/conf/lifecosys-proxy-server-keystore.jks"))
    //    val string = array.map("0x%02X ".format(_)).mkString(",")
    //    println( string)
    //    println( string.length)


    //    val data = new Array[Byte](DATA.length)


    //    var i: Int = 0
    //    while (i < data.length) {
    //      {
    //        data(i) = DATA(i).asInstanceOf[Byte]
    //        i = i + 1
    //      }
    //
    //    }
    //
    //
    //
    //    val dataShort = new Array[Short](data.length)
    //
    //
    //     i= 0
    //    while (i < data.length) {
    //      {
    //        dataShort(i) = data(i).asInstanceOf[Short]
    //        i = i + 1
    //      }
    //
    //    }
    //
    //    //
    //    println(DATA.mkString(","))
    //    println(data.mkString(","))
    //    println(dataShort.mkString(","))

    //    val proxy = ProxyServer(8080, false, true)
    //    val chainProxy = ProxyServer(8081, true, false)
    //    proxy.chainProxies += new InetSocketAddress(8081)
    //
    //    chainProxy start
    //
    //    proxy start
  }

  val DATA = Array[Short](0xfe, 0xed, 0xfe, 0xed, 0x00, 0x00, 0x00, 0x02, 0x00)
}


