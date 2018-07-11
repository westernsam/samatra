package com.springer.samatra.websockets

import java.util.concurrent.{CountDownLatch, TimeUnit}

import com.springer.samatra.websockets.WsRoutings.{WS, WSController, WriteOnly, WsRoutes}
import io.netty.handler.codec.http.cookie.DefaultCookie
import org.asynchttpclient.netty.ws.NettyWebSocket
import org.asynchttpclient.ws.{WebSocket, WebSocketListener, WebSocketUpgradeHandler}
import org.asynchttpclient.{DefaultAsyncHttpClient, DefaultAsyncHttpClientConfig}
import org.eclipse.jetty.server.{Connector, Server, ServerConnector}
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer.configureContext
import org.scalatest.Matchers._
import org.scalatest.{BeforeAndAfterAll, FunSpec}

class WebSocketEndToEndTest extends FunSpec with BeforeAndAfterAll {

  private val server = new Server() {
    addConnector(new ServerConnector(this) {
      setPort(0)
    })
  }

  server.setHandler(new ServletContextHandler(ServletContextHandler.SESSIONS) {
    sc =>
    setServer(server)

    WsRoutes(configureContext(sc), "/ws/*", new WSController {
      mount("/echo") { ws =>
        new WS {
          override def onMsg(msg: String): Unit = ws.send(s"$msg")
        }
      }
      mount("/hello/:name") { ws =>
        new WriteOnly {
          override def onConnect(): Unit = {
            ws.send(s"hello ${ws.captured("name")}")
            println("hello cookie: " + ws.cookie("name"))
            println("hello header: " + ws.header("name"))
            println("hello path: " + ws.path)
            println("hello uri: " + ws.toUri)
            println("hello param: " + ws.queryStringParamValue("q"))
          }
        }
      }
      mount("/echo-binary") { ws =>
        new WS {
          override def onMsg(msg: Array[Byte]): Unit = ws.sendBinary(msg)
          override def onMsg(msg: String): Unit = throw new UnsupportedOperationException("Binary only thank you!")
        }
      }
    })
  })

  val asyncHttpClient = new DefaultAsyncHttpClient(new DefaultAsyncHttpClientConfig.Builder().setKeepEncodingHeader(true).build())

  it("should capture named params") {
    waitForLatch("hello/sam", latch => new DefaultWebSocketListener() {
      override def onMessage(message: String): Unit = {
        message shouldBe "hello sam"
        println(s"yep got $message")
        latch.countDown()
      }
    })
  }

  it("should do the web sockets with text message") {
    waitForLatch("echo", latch => new DefaultWebSocketListener() {
      override def onOpen(websocket: WebSocket): Unit = {
        println("Sending echo")
        websocket.sendTextFrame("echo")
      }

      override def onMessage(message: String): Unit = {
        message shouldBe "echo"
        println(s"yep got $message")
        latch.countDown()
      }
    })
  }

  trait DefaultWebSocketListener extends WebSocketListener {
    override def onOpen(websocket: WebSocket): Unit = ()
    override def onClose(websocket: WebSocket, code: Int, reason: String): Unit = ()
    override def onError(t: Throwable): Unit = ()

    override def onTextFrame(payload: String, finalFragment: Boolean, rsv: Int): Unit = {
      onMessage(payload)
    }


    override def onBinaryFrame(payload: Array[Byte], finalFragment: Boolean, rsv: Int): Unit = {
      onMessage(payload)
    }

    def onMessage(message: String) : Unit = ()
    def onMessage(arr: Array[Byte]): Unit = ()
  }


  it("should do the web sockets with binary message") {
    waitForLatch("echo-binary", latch => new DefaultWebSocketListener() {
      override def onOpen(websocket: WebSocket): Unit = {
        println("Sending echo")
        websocket.sendBinaryFrame("echo".getBytes())
      }

      override def onMessage(message: Array[Byte]): Unit = {
        new String(message) shouldBe "echo"
        println(s"yep got ${new String(message)} in binary")
        latch.countDown()
      }
    })
  }

  def waitForLatch(path: String, wsl: CountDownLatch => WebSocketListener): Unit = {
    val latch = new CountDownLatch(1)
    val socket: NettyWebSocket = asyncHttpClient.prepareGet(s"$host/ws/$path")
      .addHeader("name", "header")
      .addQueryParam("q", "1")
      .addCookie(new DefaultCookie("name", "value"))
      .execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(wsl(latch)).build()).get()
    if (!latch.await(1, TimeUnit.SECONDS)) fail("Expected to finish already!")

    socket.onClose(1, host)
  }

  override protected def beforeAll(): Unit = {
    server.start()
    val connectors: Array[Connector] = server.getConnectors
    val port: Int = connectors(0).asInstanceOf[ServerConnector].getLocalPort

    host = s"ws://localhost:$port"
  }

  override protected def afterAll(): Unit = server.stop()

  var host: String = _

}
