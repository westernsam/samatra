package com.springer.link.samatra.routing

import java.io.{ByteArrayOutputStream, IOException, OutputStreamWriter, PrintWriter, UnsupportedEncodingException}
import java.security.MessageDigest
import java.text.MessageFormat
import java.time.ZoneOffset
import java.time.ZonedDateTime._
import java.util.ResourceBundle
import javax.servlet.http.{HttpServletRequest, HttpServletResponse, HttpServletResponseWrapper}
import javax.servlet.{ServletOutputStream, WriteListener}
import javax.xml.bind.DatatypeConverter

import com.springer.link.samatra.routing.Routings.HttpResp
import com.springer.link.samatra.routing.StandardResponses.WithHeaders

object CacheStrategies {

  sealed trait Visibility {
    def name: String
  }
  case object Public extends Visibility {
    override def name: String = "public"
  }
  case object Private extends Visibility {
    override def name: String = "private"
  }

  type RevalidateStrategy = (() => String)

  def noStore(rest: HttpResp): HttpResp = WithHeaders("Cache-Control" -> "no-store")(rest)

  def noRevalidate(visibility: Visibility = Private, maxAge: Option[Long] = None)(rest: => HttpResp): HttpResp = WithHeaders(CacheHeaders(visibility, maxAge): _*)(rest)

  def revalidate(visibility: Visibility = Private, maxAge: Option[Long] = None, etagStrategy: RevalidateStrategy)(rest: => HttpResp): HttpResp = {
    WithHeaders(CacheHeaders(visibility, maxAge, nonCache = true): _*)(new HttpResp {
      override def process(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
        val ifNoneMatch = req.getHeader("If-None-Match")
        val etag: String = s"""W/"${etagStrategy()}""""
        resp.setHeader("ETag", etag)

        if (etag == ifNoneMatch) resp.setStatus(304)
        else rest.process(req, resp)
      }
    })
  }

  def revalidateWithStrongEtag(visibility: Visibility = Private, maxAge: Option[Long] = None)(rest: HttpResp): HttpResp = {
    rest match {
      case FutureResponses.FutureHttpResp(_, _, _, _, _) => throw new IllegalArgumentException("Cannot do etags with future responses. Move the etag inside the future")
      case _ => WithHeaders(CacheHeaders(visibility, maxAge, nonCache = true): _*)(new HttpResp {
        override def process(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
          val ifNoneMatch = req.getHeader("If-None-Match")

          val capture = new BodyCaptureResponse(resp)
          rest.process(req, capture)

          val md5 = s""""${capture.hexOfmd5()}""""
          resp.setHeader("ETag", md5)

          if (md5 == ifNoneMatch) resp.setStatus(304)
          else resp.getOutputStream.write(capture.body())
        }
      })
    }
  }

  private def CacheHeaders(visibility: Visibility, maxAge: Option[Long], nonCache: Boolean = false): Seq[(String, String)] = {
    Seq("Cache-Control" -> s"${if (nonCache) "no-cache, " else ""}${visibility.name}${maxAge.map(t => s", max-age=$t").getOrElse("")}") ++
      maxAge.map(age => "Expires" -> now(ZoneOffset.UTC).plusSeconds(age).toInstant.toEpochMilli.toString)
  }

  //Copied from NoBodyResponse in javax.servlet.http.HttpServlet
  class BodyCaptureResponse(r: HttpServletResponse) extends HttpServletResponseWrapper(r) {
    def body(): Array[Byte] = noBody.body()

    def hexOfmd5(): String = noBody.hexOfmd5()

    private val lStrings: ResourceBundle = ResourceBundle.getBundle("javax.servlet.http.LocalStrings")
    private var noBody: NoBodyOutputStream = _
    private var writer: PrintWriter = _
    private var didSetContentLength: Boolean = false
    private var usingOutputStream: Boolean = false
    noBody = new NoBodyOutputStream(this)
    def setContentLength() {
      if (!didSetContentLength) {
        if (writer != null) {
          writer.flush()
        }
        setContentLength(noBody.getContentLength)
      }
    }

    override def setContentLength(len: Int) {
      super.setContentLength(len)
      didSetContentLength = true
    }
    override def setContentLengthLong(len: Long) {
      super.setContentLengthLong(len)
      didSetContentLength = true
    }
    override def setHeader(name: String, value: String) {
      super.setHeader(name, value)
      checkHeader(name)
    }
    override def addHeader(name: String, value: String) {
      super.addHeader(name, value)
      checkHeader(name)
    }
    override def setIntHeader(name: String, value: Int) {
      super.setIntHeader(name, value)
      checkHeader(name)
    }
    override def addIntHeader(name: String, value: Int) {
      super.addIntHeader(name, value)
      checkHeader(name)
    }
    private def checkHeader(name: String) {
      if ("content-length".equalsIgnoreCase(name)) {
        didSetContentLength = true
      }
    }
    @throws[IOException]
    override def getOutputStream: ServletOutputStream = {
      if (writer != null) {
        throw new IllegalStateException(lStrings.getString("err.ise.getOutputStream"))
      }
      usingOutputStream = true
      noBody
    }
    @throws[UnsupportedEncodingException]
    override def getWriter: PrintWriter = {
      if (usingOutputStream) {
        throw new IllegalStateException(lStrings.getString("err.ise.getWriter"))
      }
      if (writer == null) {
        val w: OutputStreamWriter = new OutputStreamWriter(noBody, getCharacterEncoding)
        writer = new PrintWriter(w)
      }
      writer
    }
  }
  /*
   * Servlet output stream that gobbles up all its data.
   */
  // file private
  class NoBodyOutputStream(resp: BodyCaptureResponse) extends ServletOutputStream {
    private val out: ByteArrayOutputStream = new ByteArrayOutputStream()
    private val md = MessageDigest.getInstance("MD5")

    def hexOfmd5(): String = DatatypeConverter.printHexBinary(md.digest(body()))

    def body(): Array[Byte] = out.toByteArray

    override def close(): Unit = {
      resp.setContentLength()
      super.close()
    }

    private val LSTRING_FILE: String = "javax.servlet.http.LocalStrings"
    private val lStrings: ResourceBundle = ResourceBundle.getBundle(LSTRING_FILE)

    private var contentLength = 0

    // file private
    def getContentLength: Int = contentLength

    def write(b: Int): Unit = {
      out.write(b)
      contentLength = contentLength + 1
    }

    override def write(buf: Array[Byte], offset: Int, len: Int): Unit = {

      if (buf == null) {
        throw new NullPointerException(
          lStrings.getString("err.io.nullArray"))
      }

      if (offset < 0 || len < 0 || offset + len > buf.length) {
        var msg = lStrings.getString("err.io.indexOutOfBounds")

        val msgArgs = new Array[Object](3)
        msgArgs(0) = Integer.valueOf(offset)
        msgArgs(1) = Integer.valueOf(len)
        msgArgs(2) = Integer.valueOf(buf.length)

        msg = MessageFormat.format(msg, msgArgs)

        throw new IndexOutOfBoundsException(msg)
      }

      out.write(buf, offset, len)
      contentLength += len
    }
    def isReady: Boolean = false
    def setWriteListener(writeListener: WriteListener): Unit = {}
  }
}
