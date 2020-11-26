package com.springer.samatra.websockets

import java.net.HttpCookie
import java.nio.ByteBuffer
import java.security.Principal
import java.util
import javax.websocket._
import javax.websocket.server.{HandshakeRequest, ServerContainer, ServerEndpoint, ServerEndpointConfig}
import scala.jdk.CollectionConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future, blocking}

object WsRoutings {
  case class WsRoute(path: String, socket: WSSend => WS) {
    override def toString: String = {
      s"WS   ws://(hostname)/${path.padTo(32, ' ')}"
    }
  }

  class WsRoutes {
    val routes: ListBuffer[WsRoute] = ListBuffer()
  }

  class SessionBackedWSSend(val sess: Session) extends WSSend {
    override def id: String = sess.getId
    override def sendBinary(msg: Array[Byte]): Unit = sendBinary(Future.successful(msg))(ExecutionContext.global)
    override def sendBinary(msg: Future[Array[Byte]])(implicit ex: ExecutionContext): Future[Unit] = {
      msg.flatMap { bytes =>
        val value: util.concurrent.Future[Void] = sess.getAsyncRemote.sendBinary(ByteBuffer.wrap(bytes))
        Future {
          blocking {
            value.get
          }
        }
      }
    }
    override def close(code: Int, msg: String): Unit = sess.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(code), msg))
    override def ping(bytes: Array[Byte]): Unit = sess.getAsyncRemote.sendPing(ByteBuffer.wrap(bytes))
    override def pong(bytes: Array[Byte]): Unit = sess.getAsyncRemote.sendPong(ByteBuffer.wrap(bytes))
    override def send(msg: String): Unit = send(Future.successful(msg))(ExecutionContext.global)
    override def send(msg: Future[String])(implicit ex: ExecutionContext): Future[Unit] = {
      msg.flatMap { str =>
        val value: util.concurrent.Future[Void] = sess.getAsyncRemote.sendText(str)
        Future {
          blocking {
            value.get
          }
        }
      }
    }

    override def broadcast(msg: String, p: WSSend => Boolean = _ => true): List[WSSend] =
      sess.getOpenSessions.asScala.filter(_.isOpen).map(s => new SessionBackedWSSend(s)).filter(p)
        .map { ws => ws.send(msg); ws }.toList

    override def broadcast(msg: Future[String], p: WSSend => Boolean)(implicit ex: ExecutionContext): Future[List[WSSend]] = {
      val matchingSockets = sess.getOpenSessions.asScala.filter(_.isOpen).map(s => new SessionBackedWSSend(s)).filter(p)

      Future.sequence(matchingSockets.toList.map { ws =>
        ws.send(msg).map(_ => ws)
      })
    }

    override def captured(name: String): String = sess.getPathParameters.asScala.toMap.apply(name)
    override def path: String = sess.getRequestURI.getPath
    override def toUri: String = sess.getRequestURI.toString
    override def queryStringParamValue(name: String): String = {
      val scala: mutable.Map[String, util.List[String]] = sess.getRequestParameterMap.asScala
      scala.get(name).map(_.asScala).getOrElse(Seq.empty).headOption.orNull
    }

    override def queryStringParamValues(name: String): Set[String] = sess.getRequestParameterMap.asScala.get(name).map(_.asScala.toSet).getOrElse(Set.empty)
    override def cookie(cookieName: String): Option[String] = cookies.find(_.getName == cookieName).map(_.getValue)

    override def cookies: Seq[HttpCookie] = {
      val cookies1 = HttpCookie.parse(header("cookie").orNull)
      cookies1.asScala.toSeq
    }

    override def header(name: String): Option[String] = headers(name.toLowerCase).headOption
    override def headers(name: String): Seq[String] = upgradeHeaders.getOrElse(name.toLowerCase, Seq.empty).toSeq

    override def user: Option[Principal] = Option(sess.getUserProperties.get("user")).map(_.asInstanceOf[Principal])

    private lazy val upgradeHeaders: Map[String, ListBuffer[String]] = {
      sess.getUserProperties.get("headers").asInstanceOf[Map[String, ListBuffer[String]]]
    }
  }

  @ServerEndpoint("/*")
  class SamatraWebSocket(ws: WSSend => WS, pattern: String) {
    var soc: WS = _
    var socComms: WSSend = _

    @OnOpen def onWebSocketConnect(sess: Session): Unit = {
      socComms = new SessionBackedWSSend(sess)
      soc = ws(socComms)
      try {
        soc.onConnect()
      } catch {
        case t: Throwable => sess.close(new CloseReason(CloseReason.CloseCodes.CLOSED_ABNORMALLY, t.getMessage))
      }
    }

    @OnMessage def onWebSocketText(message: String): Unit = try {
      soc.onMsg(message)
    } catch {
      case t: Throwable => socComms.close(CloseReason.CloseCodes.CLOSED_ABNORMALLY.getCode, t.getMessage)
    }
    @OnMessage def onWebSocketBinary(message: Array[Byte]): Unit = try {
      soc.onMsg(message)
    } catch {
      case t: Throwable => socComms.close(CloseReason.CloseCodes.CLOSED_ABNORMALLY.getCode, t.getMessage)
    }
    @OnClose def onWebSocketClose(reason: CloseReason): Unit = {
      socComms.close(reason.getCloseCode.getCode, reason.getReasonPhrase)
      soc.onEnd(reason.getCloseCode.getCode)
    }
    @OnError def onWebSocketError(cause: Throwable): Unit = soc.onError(cause)
  }

  object WsRoutes {
    def apply(mountOn: ServerContainer, pathSpec: String, controllers: WsRoutes*): Unit = {
      controllers.flatMap(_.routes).foreach { r =>
        mountOn.addEndpoint(new ServerEndpointConfig {

          override def getEndpointClass: Class[_] = classOf[SamatraWebSocket]
          override def getConfigurator: ServerEndpointConfig.Configurator = new ServerEndpointConfig.Configurator {
            override def getEndpointInstance[T](endpointClass: Class[T]): T = if (endpointClass == classOf[SamatraWebSocket]) {
              new SamatraWebSocket(r.socket, r.path).asInstanceOf[T]
            } else throw new IllegalStateException()

            override def modifyHandshake(sec: ServerEndpointConfig, request: HandshakeRequest, response: HandshakeResponse): Unit = {
              val headers: Map[String, mutable.Buffer[String]] = request.getHeaders.asScala.map { case (k, v) => k.toLowerCase -> v.asScala }.toMap
              sec.getUserProperties.put("headers", headers)
              sec.getUserProperties.put("user", request.getUserPrincipal)
              super.modifyHandshake(sec, request, response)
            }
          }
          override def getPath: String = pathSpec.substring(0, pathSpec.length - 2) + r.path.split("/").map {
            case pattern if pattern.startsWith(":") => s"{${pattern.substring(1)}}"
            case p => p
          }.mkString("/")

          override def getSubprotocols: util.List[String] = util.Arrays.asList("text", "binary")
          override def getDecoders: util.List[Class[_ <: Decoder]] = util.Collections.emptyList()
          override def getExtensions: util.List[Extension] = util.Collections.emptyList()
          override val getUserProperties: util.Map[String, AnyRef] = new util.HashMap[String, AnyRef]
          override def getEncoders: util.List[Class[_ <: Encoder]] = util.Collections.emptyList()
        })
      }
    }
  }

  trait WSSend {
    def ping(bytes: Array[Byte]): Unit
    def pong(bytes: Array[Byte]): Unit
    def send(msg: String): Unit
    def send(msg: Future[String])(implicit ex: ExecutionContext): Future[Unit]
    def sendBinary(msg: Array[Byte]): Unit
    def sendBinary(msg: Future[Array[Byte]])(implicit ex: ExecutionContext): Future[Unit]
    def broadcast(msg: String, p: WSSend => Boolean = _ => true): List[WSSend]
    def broadcast(msg: Future[String], p: WSSend => Boolean)(implicit ex: ExecutionContext): Future[List[WSSend]]

    def close(code: Int, msg: String): Unit

    def captured(name: String): String
    def path: String
    def toUri: String
    def queryStringParamValue(name: String): String
    def queryStringParamValues(name: String): Set[String]

    def cookies: Seq[HttpCookie]
    def cookie(cookieName: String): Option[String]
    def header(name: String): Option[String]
    def headers(name: String): Seq[String]
    def user: Option[Principal]
    def id: String
  }

  trait WS {
    def onConnect(): Unit = ()
    def onMsg(msg: String): Unit
    def onMsg(msg: Array[Byte]): Unit = throw new UnsupportedOperationException("Binary data unsupported")
    def onEnd(code: Int): Unit = ()
    def onError(throwable: Throwable): Unit = ()
  }

  trait WriteOnly extends WS {
    final override def onMsg(msg: String): Unit = ()
    final override def onMsg(msg: Array[Byte]): Unit = ()
  }

  abstract class WSController extends WsRoutes {
    def mount(path: String)(ws: WSSend => WS): Unit = {
      routes.append(WsRoute(path, ws))
    }
  }
}
