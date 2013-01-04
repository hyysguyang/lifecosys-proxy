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

import org.apache.commons.codec.binary.Base64
import java.util.regex.Pattern
import org.apache.commons.io.IOUtils

/**
 *
 *
 * @author Young Gu 
 * @version 1.0 1/4/13 3:06 PM
 */
class GFWList {
  val excludeList = scala.collection.mutable.ArrayBuffer[GFWListRule]()
  val matchList = scala.collection.mutable.ArrayBuffer[GFWListRule]()

  def getContent = new String(new Base64().decode(IOUtils.toString(getClass.getResourceAsStream("/gfwlist.txt"))))

  def parseRules = {
    scala.io.Source.fromString(getContent).getLines().toList.foreach {
      case line: String if line.startsWith("@@||") => excludeList += HttpStringGFWListRule(line.substring(4))
      case line: String if line.startsWith("||") => matchList += HostMatchGFWListRule(line.substring(2))
      case line: String if line.startsWith("|http") => matchList += UrlStartMatchGFWListRule(line.substring(1))
      case line: String if line.startsWith("/") => matchList += RegexMatchGFWListRule(line.substring(1))
      case line: String if line.startsWith("!") =>
      case line: String if line.trim.length > 0 => matchList += HttpStringGFWListRule(line)
      case line: String =>
    }
  }

  def isBlocked(url: String) = {
    !excludeList.exists(_.matchWith(url)) && matchList.exists(_.matchWith(url))
  }

}


sealed trait GFWListRule {
  def matchWith(url: String): Boolean
}

case class HttpStringGFWListRule(rule: String) extends GFWListRule {
  def matchWith(url: String): Boolean = url.startsWith("http://") && url.indexOf(rule) != -1
}

case class UrlStartMatchGFWListRule(rule: String) extends GFWListRule {
  def matchWith(url: String): Boolean = url.startsWith(rule)
}

case class RegexMatchGFWListRule(rule: String) extends GFWListRule {
  val pattern = Pattern.compile(rule)

  def matchWith(url: String): Boolean = {
    return pattern.matcher(url).matches
  }
}

case class HostMatchGFWListRule(rule: String) extends GFWListRule {
  def matchWith(url: String): Boolean = rule == Utils.extractHostAndPort(url)._1
}
