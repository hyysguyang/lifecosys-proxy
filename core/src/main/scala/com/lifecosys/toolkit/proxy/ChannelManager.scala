package com.lifecosys.toolkit.proxy

import java.net.SocketAddress
import scala.collection.immutable.Queue
import org.jboss.netty.channel.{ Channel, ChannelFuture }
import scala.util.{ Failure, Success, Try }
import java.util.TimerTask

/**
 *
 *
 * @author Young Gu
 * @version 1.0 8/16/13 3:07 PM
 */

trait ChannelManager {
  protected val cachedChannelFutures = scala.collection.mutable.Map[SocketAddress, Queue[ChannelFuture]]()

  def get(host: SocketAddress) = synchronized {
    Try(getChannelFutures(host).dequeue) match {
      case Success((future, tails)) ⇒ {
        cachedChannelFutures += host -> tails
        Some(future)
      }
      case Failure(e) ⇒ None
    }
  }
  def getChannelFutures(host: SocketAddress) = cachedChannelFutures.get(host).getOrElse(Queue[ChannelFuture]())

  def add(host: SocketAddress, channelFuture: ChannelFuture) = synchronized {
    cachedChannelFutures += host -> getChannelFutures(host).enqueue(channelFuture)
  }

  def removeClosedChannel(host: SocketAddress) = synchronized {
    val futures = getChannelFutures(host).filter(_.getChannel.isConnected)
    if (futures.isEmpty)
      cachedChannelFutures.remove(host)
    else
      cachedChannelFutures += host -> futures
  }

  override def toString: String = {
    s"ChannelManager: ${cachedChannelFutures.size} cached\n" + cachedChannelFutures.map {
      case (host, channelFutures) ⇒ s"$host => ${channelFutures.size} channel cached. \n\t${channelFutures.map(_.getChannel).mkString("\n\t")}"
    }.mkString("\n")
  }
}

//We need provide each ChannelManager for HTTP and HTTPS to void the complexity of maintain channel handle between HTTP/HTTPS
object HttpChannelManager extends ChannelManager
object HttpsChannelManager extends ChannelManager

trait HttpsRequestManager {
  protected val cachedChannelFutures = scala.collection.mutable.Map[String, Channel]()
  def get(requestID: String) = synchronized(cachedChannelFutures.get(requestID))

  def add(requestID: String, channel: Channel) = synchronized(cachedChannelFutures += requestID -> channel)

  def remove(requestID: String) = synchronized(cachedChannelFutures.remove(requestID))
  override def toString: String = {
    s"Requests: ${cachedChannelFutures.size} pending\n" + cachedChannelFutures.mkString("\n")
  }
}

object DefaultHttpsRequestManager extends HttpsRequestManager

trait TimerTaskManager {
  protected val cachedChannelFutures = scala.collection.mutable.Map[String, TimerTask]()
  def get(requestID: String) = synchronized(cachedChannelFutures.get(requestID))

  def add(requestID: String, timerTask: TimerTask) = synchronized(cachedChannelFutures += requestID -> timerTask)

  def remove(requestID: String) = synchronized(cachedChannelFutures.remove(requestID))
  override def toString: String = {
    s"TimerTaskManager: ${cachedChannelFutures.size} task\n" + cachedChannelFutures.mkString("\n")
  }
}

object DefaultTimerTaskManager extends TimerTaskManager

case class Request(requestID: String, browserChannel: Channel, channel: Channel)
trait RequestManager {
  protected val requests = scala.collection.mutable.ArrayBuffer[Request]()

  def add(request: Request) = synchronized {
    requests += request
  }

  def remove(requestID: String) = synchronized {
    requests --= requests.filter(_.requestID == requestID)
  }

  override def toString: String = {
    s"RequestManager: ${requests.size} pending\n" + requests.mkString("\n")
  }
}

object DefaultRequestManager extends RequestManager