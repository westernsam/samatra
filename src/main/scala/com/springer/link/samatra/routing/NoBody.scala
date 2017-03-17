package com.springer.link.samatra.routing

import java.io.{IOException, OutputStreamWriter, PrintWriter, UnsupportedEncodingException}
import java.text.MessageFormat
import java.util.ResourceBundle
import javax.servlet.http.{HttpServletResponse, _}
import javax.servlet.{ServletOutputStream, WriteListener}

import com.springer.link.samatra.routing.Routings._

case class NoBody(body: (Request) => HttpResp) extends (Request => HttpResp) {
  override def apply(req: Request): HttpResp = {
    val underlyingBody: HttpResp = body(req)

    new HttpResp {
      override def process(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
        underlyingBody.process(req, new NoBodyResponse(resp))
      }
    }
  }

  //Copied from NoBodyResponse in javax.servlet.http.HttpServlet
  class NoBodyResponse(r: HttpServletResponse) extends HttpServletResponseWrapper(r) {
    private val lStrings: ResourceBundle = ResourceBundle.getBundle("javax.servlet.http.LocalStrings")
    private var noBody: NoBodyOutputStream = null
    private var writer: PrintWriter = null
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
  class NoBodyOutputStream(resp: NoBodyResponse) extends ServletOutputStream {

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

      contentLength += len
    }


    def isReady: Boolean = false

    def setWriteListener(writeListener: WriteListener): Unit = {}
  }

}
