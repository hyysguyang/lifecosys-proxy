package com.lifecosys.toolkit.proxy.server

import scala.concurrent.duration._
import akka.pattern.ask
import akka.util.Timeout
import akka.actor._
import spray.can.{ HostConnectorSetup, Http }
import spray.util._
import akka.io.IO
import spray.can.HostConnectorInfo
import spray.http.Uri.Authority
import spray.http.HttpRequest
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import java.net.URLDecoder

object SprayProxy extends App {

  implicit val system = ActorSystem()

  // the handler actor replies to incoming HttpRequests
  val handler = system.actorOf(Props(new DemoService), name = "handler")

  //  println(system.settings.config.getConfig("spray.can.parsing").root().render())
  //  println("####################################################")
  //  println(system.settings.config.getConfig("spray.can.server.parsing").root().render())
  //
  //
  IO(Http) ! Http.Bind(handler, interface = "localhost", port = 8080)

  class DemoService extends Actor with SprayActorLogging {
    implicit val timeout: Timeout = 120.second // for the actor 'asks'

    val index = new AtomicInteger(0)

    def stripHost(uri: String): String = {
      val httpPattern = Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE)
      if (!httpPattern.matcher(uri).matches())
        uri
      else {
        val noHttpUri: String = uri.substring(uri.indexOf("://") + 3)
        val slashIndex = noHttpUri.indexOf("/")
        if (slashIndex == -1) "/"
        else noHttpUri.substring(slashIndex)
      }
    }

    def receive = {
      case c: Http.Connected ⇒
        sender ! Http.Register(self)
      case request: HttpRequest ⇒
        val browser = sender
        import system.dispatcher // execution context for future transformations below

        var query = request.uri.query.toString

        println("###########################")
        println(request.uri.toString())

        val index = query.indexOf('?') + 1
        if (index > 0) {
          query = query.take(index) + URLDecoder.decode(query.substring(index), "UTF-8")
        }
        val authority: Authority = request.uri.authority
        val port: Int = if (authority.port > 0) authority.port else 80
        for {
          HostConnectorInfo(hostConnector, _) ← IO(Http) ? HostConnectorSetup(authority.host.address, port)
          response ← hostConnector.ask(request).mapTo[Any]
          //          response ← hostConnector.ask(request.copy(uri = request.uri.copy(scheme = "", authority = Authority.Empty, query = Query(query, Uri.ParsingMode.RelaxedWithRawQuery)))).mapTo[Any]
        } {
          browser ! response
        }

    }

  }

}