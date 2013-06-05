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

package com.lifecosys.toolkit.logging

import android.util.Log
import org.jboss.netty.logging.{ InternalLogLevel, InternalLogger, InternalLoggerFactory }

/**
 *
 *
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 1/12/13 5:01 PM
 */
class AndroidLogger(tag: String, level: String) extends Logger {

  object Level extends Enumeration("TRACE", "DEBUG", "INFO", "WARN", "ERROR") {
    type WeekDay = Value
    val TRACE, DEBUG, INFO, WARN, ERROR = Value

    def valueOf(level: String) = values.find(_.toString == level).getOrElse(TRACE)
  }

  import Level._

  val loggerLevel = valueOf(level.toUpperCase)

  def trace(msg: ⇒ Any) = if (TRACE >= loggerLevel) debug(msg)

  def trace(msg: ⇒ Any, t: Throwable) = if (TRACE >= loggerLevel) debug(msg, t)

  def debug(msg: ⇒ Any) = if (DEBUG >= loggerLevel) Log.d(tag, msg.toString)

  def debug(msg: ⇒ Any, t: Throwable) = if (DEBUG >= loggerLevel) Log.d(tag, msg.toString, t)

  def info(msg: ⇒ Any) = if (INFO >= loggerLevel) Log.i(tag, msg.toString)

  def info(msg: ⇒ Any, t: Throwable) = if (INFO >= loggerLevel) Log.i(tag, msg.toString, t)

  def warn(msg: ⇒ Any) = if (WARN >= loggerLevel) Log.w(tag, msg.toString)

  def warn(msg: ⇒ Any, t: Throwable) = if (WARN >= loggerLevel) Log.w(tag, msg.toString, t)

  def error(msg: ⇒ Any) = if (ERROR >= loggerLevel) Log.e(tag, msg.toString)

  def error(msg: ⇒ Any, t: Throwable) = if (ERROR >= loggerLevel) Log.e(tag, msg.toString, t)

}

class AndroidInternalLoggerFactory extends InternalLoggerFactory {

  def newInstance(name: String): InternalLogger = new InternalLogger {
    def isDebugEnabled: Boolean = true

    def isInfoEnabled: Boolean = true

    def isWarnEnabled: Boolean = true

    def isErrorEnabled: Boolean = true

    def isEnabled(level: InternalLogLevel): Boolean = false

    def debug(msg: String) = androidLogger.debug(msg)

    def debug(msg: String, cause: Throwable) = androidLogger.debug(msg, cause)

    def info(msg: String) = androidLogger.info(msg)

    def info(msg: String, cause: Throwable) = androidLogger.info(msg, cause)

    def warn(msg: String) = androidLogger.warn(msg)

    def warn(msg: String, cause: Throwable) = androidLogger.warn(msg, cause)

    def error(msg: String) = androidLogger.error(msg)

    def error(msg: String, cause: Throwable) = androidLogger.error(msg, cause)

    def log(level: InternalLogLevel, msg: String) {}

    def log(level: InternalLogLevel, msg: String, cause: Throwable) {}
  }
}
