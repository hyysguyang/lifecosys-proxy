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

package com.lifecosys.toolkit

import java.lang.Throwable
import org.slf4j.{MDC, LoggerFactory}

/**
 *
 *
 * @author <a href="mailto:hyysguyang@gamil.com">Young Gu</a>
 * @author <a href="mailto:Young.Gu@lifecosys.com">Young Gu</a>
 * @version 1.0 1/2/13 1:44 AM
 */

object Logger {
  def apply(name: String) = new Logger(name)

  def apply(clazz: Class[_]) = new Logger(clazz.getName)
}

class Logger(name: String) {
  val logger = LoggerFactory.getLogger(name)

  def messageProcess(msg: => Any) = {
    val caller = Thread.currentThread().getStackTrace()(3)
    MDC.put("location", "%s:[%s]".format(caller.getClassName, caller.getLineNumber));
    try {
      msg toString
    }
    catch {
      case e: Throwable =>
        logger.warn("Exception when loger", e)
        e.getMessage
    }
  }

  def trace(msg: => Any) = if (logger.isTraceEnabled) logger.trace(messageProcess(msg))

  def trace(msg: => Any, t: Throwable) = if (logger.isTraceEnabled) logger.trace(messageProcess(msg), t)

  def debug(msg: => Any) = if (logger.isDebugEnabled) logger.debug(messageProcess(msg))

  def debug(msg: => Any, t: Throwable) = if (logger.isDebugEnabled) logger.debug(messageProcess(msg), t)

  def info(msg: => Any) = if (logger.isInfoEnabled) logger.info(messageProcess(msg))

  def info(msg: => Any, t: Throwable) = if (logger.isInfoEnabled) logger.info(messageProcess(msg), t)

  def warn(msg: => Any) = if (logger.isWarnEnabled) logger.warn(messageProcess(msg))

  def warn(msg: => Any, t: Throwable) = if (logger.isWarnEnabled) logger.warn(messageProcess(msg), t)

  def error(msg: => Any) = if (logger.isErrorEnabled) logger.error(messageProcess(msg))

  def error(msg: => Any, t: Throwable) = if (logger.isErrorEnabled) logger.error(messageProcess(msg), t)

}

