package com.springer.samatra.routing

import java.nio.file.Paths

import com.springer.samatra.routing.CacheStrategies._
import com.springer.samatra.routing.FutureResponses.Implicits.fromFuture
import com.springer.samatra.routing.FutureResponses._
import com.springer.samatra.routing.Routings.{Controller, HeadersOnly, HttpResp, Routes}
import com.springer.samatra.routing.StandardResponses.Implicits._
import com.springer.samatra.routing.StandardResponses._
import io.netty.handler.codec.http.DefaultHttpHeaders
import org.asynchttpclient.{DefaultAsyncHttpClient, DefaultAsyncHttpClientConfig, Response}
import org.eclipse.jetty.server.handler.gzip.GzipHandler
import org.eclipse.jetty.server.{Connector, Server, ServerConnector}
import org.eclipse.jetty.servlet.{ServletContextHandler, ServletHolder}
import org.scalatest.Matchers._
import org.scalatest.{BeforeAndAfterAll, FunSpec}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class EndToEndTest extends FunSpec with BeforeAndAfterAll {

  private val server = new Server() {
    addConnector(new ServerConnector(this) {
      setPort(0)
    })
  }

  private val handler = new GzipHandler()
  handler.setHandler(new ServletContextHandler() {
    setContextPath("/test")
    addServlet(new ServletHolder(
      Routes(
        new Controller {
          get("/himynameis/:name")(req => s"hi ${req.captured("name")}")
          get("/file") { _ =>
            WithHeaders("Content-Type" -> "application/xml") {
              Paths.get("build.sbt")
            }
          }
          get("/uri")(_.toUri)
          get("/querystringmap") {
            _.queryStringMap.map { case (k, v) => s"$k->${v.mkString}" }.mkString("|")
          }
        },
        new Controller {
          head("/head") { _ => HeadersOnly("header" -> "value") }
          post("/post") { req => req.bodyAsStream }
          get("/getandpost") { _ => "get" }
          post("/getandpost") { _ => "post" }
          get("^/regex/year/(\\d\\d\\d\\d)$".r) { _ => Halt(500, Some(new IllegalStateException("servlet path takes precedence"))) }
        })), "/*")

    addServlet(new ServletHolder(Routes(new Controller {
      get("^/year/(\\d\\d\\d\\d)$".r) { req => s"hell0 the year ${req.captured(0)}" }
      get("^/date/(.*)$".r) { req => s"hell0 the date ${req.captured(0)}" }
    })), "/regex/*")

    addServlet(new ServletHolder(Routes(new Controller {
      get("/no-store") { req => noStore("Should have Cache-Control: no-store header") }
      get("/no-revalidate") { req => noRevalidate(Public, Some(600))("Should have Cache-Control: public, max-age=600 header") }
      get("/etag/:name") { req =>
        Future {
          revalidateWithStrongEtag(Private) {
            {
              s"Should have ETag for ${req.captured("name")}"
            }
          }
        }
      }
      get("/weakEtag/:name") { req =>
        Future {
          CacheStrategies.revalidate(Private, etagStrategy = { () => req.captured("name").hashCode.toString }) {
            {
              s"Should have ETag for ${req.captured("name")}"
            }
          }
        }
      }
    })), "/caching/*")

    addServlet(new ServletHolder(
      Routes(new Controller {

        //with implicits
        get("/morethanone/:type") { req =>
          Future[HttpResp] {
            req.captured("type") match {
              case "redirect" => Redirect("/getandpost")
              case "string" => "String"
              case "Error" => Halt(500)
              case "NotFound" => Halt(404)
              case "file" => WithHeaders("Content-Type" -> "application/xml") {
                Paths.get("build.sbt")
              }
              case "headers" => WithHeaders("hi" -> "there")("body")
              case "cookies" =>
                WithCookies(AddCookie("cookie", "tasty"))("body")
              case "securedcookies" =>
                WithCookies(AddCookie("cookie", "tasty", httpOnly = true))("body")
            }
          }
        }

        //use explicits
        get("/timeout") { req =>
          fromFutureWithTimeout(timeout = 100, Future {
            Thread.sleep(300)
            fromString("oh hello there - i didn't expect to see you")
          }, logThreadDumpOnTimeout = true)
        }

      })), "/future/*")
  })

  server.setHandler(handler)

  describe("Caching") {

    it("Adds no-store for noStore strategy") {
      get("/test/caching/no-store").getHeader("Cache-Control") shouldBe "no-store"
    }

    it("Adds visibility and max-age headers for noRevalidate strategy") {
      val res = get("/test/caching/no-revalidate")
      res.getHeader("Cache-Control") shouldBe "public, max-age=600"
      res.getHeader("Expires") should not be null
    }

    it("Adds Weak ETag support") {
      val res = get("/test/caching/weakEtag/sam")
      res.getStatusCode shouldBe 200
      res.getHeader("Cache-Control") shouldBe "no-cache, private"
      val etag: String = res.getHeader("ETag")
      etag should startWith("W/\"")

      val res2 = get(s"/test/caching/weakEtag/sam", Map("Accept-Encoding" -> Seq("gzip"), "If-None-Match" -> Seq(etag)))

      res2.getStatusCode shouldBe 304

      val res3 = get(s"/test/caching/weakEtag/andy", Map("Accept-Encoding" -> Seq("gzip"), "If-None-Match" -> Seq(etag)))

      res3.getStatusCode shouldBe 200
      res3.getHeader("Content-Encoding") shouldBe "gzip"
    }

    it("Adds Strong ETag support") {

      val res = get("/test/caching/etag/sam")
      res.getStatusCode shouldBe 200
      res.getHeader("Cache-Control") shouldBe "no-cache, private"
      val etag: String = res.getHeader("ETag")
      etag should not be null

      val res2 = get(s"/test/caching/etag/sam", Map("Accept-Encoding" -> Seq("gzip"), "If-None-Match" -> Seq(etag)))

      res2.getStatusCode shouldBe 304

      val res3 = get(s"/test/caching/etag/andy", Map("Accept-Encoding" -> Seq("gzip"), "If-None-Match" -> Seq(etag)))

      res3.getStatusCode shouldBe 200
      res3.getHeader("Content-Encoding") shouldBe "gzip"

    }
  }

  describe("Routes") {
    it("should return 404 for not found route") {
      get("/test/querystringmap?1=a&1=b&2=c&3=%2623").getResponseBody shouldBe "1->ab|2->c|3->&23"
    }

    it("should give query string map") {
      get("/test/missing").getStatusCode shouldBe 404
    }

    it("should return headers only for HEAD") {
      head("/test/missing").getStatusCode shouldBe 404

      val resp: Response = head("/test/head")
      resp.getStatusCode shouldBe 200
      resp.getHeader("header") shouldBe "value"
      resp.getHeader("Content-Length") shouldBe "0"

      val resp2: Response = head("/test/future/morethanone/string")
      resp2.getStatusCode shouldBe 200
      resp2.getHeader("Content-Length") shouldBe "6"
    }

    it("should return 405 for invalid method") {
      val wrong = post("/test/himynameis/Sam")
      wrong.getStatusCode shouldBe 405
      wrong.getHeader("Allow") shouldBe "GET, HEAD"

      post("/test/missing").getStatusCode shouldBe 404

      post("/test/post", "body").getResponseBody shouldBe "body"
    }

    it("HEAD should return 200, 302, 404 and 500 error codes") {
      head("/test/future/morethanone/Error").getStatusCode shouldBe 500
      head("/test/future/morethanone/NotFound").getStatusCode shouldBe 404
      head("/test/future/morethanone/redirect").getStatusCode shouldBe 302
      head("/test/future/morethanone/string").getStatusCode shouldBe 200
      head("/test/future/morethanone/headers").getHeader("hi") shouldBe "there"

      val cookie = head("/test/future/morethanone/cookies").getCookies.asScala.collectFirst {
        case c if c.name() == "cookie" => c.value()
      }

      cookie shouldBe Some("tasty")
    }

    it("should return 500 for timeout") {
      val res = get("/test/future/timeout")
      res.getStatusCode shouldBe 500
      val body: String = res.getResponseBody
      body should include("java.lang.Thread.State: TIMED_WAITING")
      body should include("at java.lang.Thread.sleep(Native Method)")
    }

    it("should parse path params") {
      get("/test/himynameis/Sam").getResponseBody shouldBe "hi Sam"
    }

    it("should not URL decode parsed path params") {
      get("/test/himynameis/Sam%2FOwen").getResponseBody shouldBe "hi Sam%2FOwen"
    }

    it("should parse regex params") {
      get("/test/regex/year/2000").getResponseBody shouldBe "hell0 the year 2000"
    }

    it("should not URL decode parsed regex params") {
      get("/test/regex/date/01%2F01%2F2000").getResponseBody shouldBe "hell0 the date 01%2F01%2F2000"
    }

    it("should be able to get and post from same uri") {
      get("/test/getandpost").getResponseBody shouldBe "get"
      post("/test/getandpost").getResponseBody shouldBe "post"
    }

    it("should be able to set cookies") {
      get("/test/future/morethanone/cookies").getCookies.asScala.head.value() shouldBe "tasty"
    }

    it("should be able to retrieve request uri") {
      get("/test/uri?foo=bar#qunx").getResponseBody shouldBe s"$host/test/uri?foo=bar"
    }

    it("should be able to use GzipHandler") {
      def shouldBeGzipped(path: String): Unit = {
        get(s"$path", Map("Accept-Encoding" -> Seq("gzip"))).getHeader("Content-Encoding") shouldBe "gzip"
      }

      shouldBeGzipped("/test/file")
      shouldBeGzipped("/test/future/morethanone/file")
      shouldBeGzipped("/test/caching/etag/andy")
    }
  }

  val asyncHttpClient = new DefaultAsyncHttpClient(new DefaultAsyncHttpClientConfig.Builder().setKeepEncodingHeader(true).build())

  def get(path: String, headers: Map[String, Seq[String]] = Map.empty): Response = {
    val hs = new DefaultHttpHeaders()
    headers.foreach { case (k, v) => hs.add(k, v.asJava) }

    asyncHttpClient.prepareGet(s"$host$path")
      .setHeaders(hs)
      .execute().get()
  }

  def head(path: String): Response = asyncHttpClient.prepareHead(s"$host$path").execute().get()
  def post(path: String, body: String = ""): Response = asyncHttpClient.preparePost(s"$host$path").setBody(body).execute().get()

  override protected def beforeAll(): Unit = {
    server.start()
    val connectors: Array[Connector] = server.getConnectors
    val port: Int = connectors(0).asInstanceOf[ServerConnector].getLocalPort

    host = s"http://localhost:$port"
  }

  override protected def afterAll(): Unit = server.stop()

  var host: String = _
}
