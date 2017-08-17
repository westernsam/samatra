package com.springer.samatra.routing

import java.io.{InputStream, OutputStream}
import java.nio.file.{Files, Path}
import javax.servlet.http.{Cookie, HttpServletRequest, HttpServletResponse}

import com.springer.samatra.routing.Routings.HttpResp

import scala.language.implicitConversions

object StandardResponses {

  case class Redirect(location: String) extends HttpResp {
    override def process(req: HttpServletRequest, resp: HttpServletResponse): Unit = resp.sendRedirect(location)
  }

  case class Html(html: String) extends HttpResp {
    override def process(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
      if (resp.getContentType == null)
        resp.setContentType("text/html;charset=utf-8")
      resp.getOutputStream.write(html.getBytes())
    }
  }

  case class Halt(statusCode: Int, exception: Option[Throwable] = None) extends HttpResp {
    override def process(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
      exception match {
        case Some(thrown) => req.setAttribute("javax.servlet.error.exception", thrown)
        case None =>
      }
      resp.sendError(statusCode)
    }
  }

  case class WithHeaders(headers: (String, String)*)(implicit rest: HttpResp) extends HttpResp {
    override def process(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
      headers.foreach(t => resp.setHeader(t._1, t._2))
      rest.process(req, resp)
    }
  }

  sealed abstract class CookieAction
  case class AddCookie(name: String, value: String, maxAge: Option[Int] = None, domain: String = ".springer.com", path: String = "/", httpOnly: Boolean = false) extends CookieAction
  case class RemoveCookie(name: String) extends CookieAction

  case class WithCookies(cookies: Seq[CookieAction])(val rest: HttpResp) extends HttpResp {
    override def process(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
      cookies.foreach {
        case add: AddCookie =>
          val cookie = new Cookie(add.name, add.value)
          cookie.setPath(add.path)
          cookie.setDomain(add.domain)
          cookie.setHttpOnly(add.httpOnly)
          add.maxAge.foreach(cookie.setMaxAge)
          resp.addCookie(cookie)
        case remove: RemoveCookie =>
          val cookie = new Cookie(remove.name, "")
          cookie.setMaxAge(0)
          resp.addCookie(cookie)
      }
      rest.process(req, resp)
    }
  }

  case class StringResp(str: String) extends HttpResp {
    override def process(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
      resp.setStatus(200)
      if (resp.getContentType == null)
        resp.setContentType("text/plain")
      resp.getOutputStream.write(str.getBytes)
    }
  }

  case class PathResp(path: Path) extends HttpResp {
    override def process(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
      resp.setStatus(200)
      if (resp.getContentType == null)
        resp.setContentType("text/plain")
      resp.getOutputStream.write(Files.readAllBytes(path))
    }
  }

  case class ReqToRespResp(f: (HttpServletRequest, HttpServletResponse) => Unit) extends HttpResp {
    override def process(req: HttpServletRequest, resp: HttpServletResponse): Unit = f(req, resp)
  }

  case class InputStreamResp(in: InputStream) extends HttpResp {
    override def process(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
      resp.setStatus(200)

      def copy(input: InputStream, output: OutputStream): Unit = {
        val buffer = new Array[Byte](4 * 1024)
        var n = 0
        while ( {
          n = input.read(buffer)
          n > -1
        }) {
          output.write(buffer, 0, n)
        }
      }
      copy(in, resp.getOutputStream)
    }
  }

  object Implicits {
    implicit def fromString(str: String): HttpResp = StringResp(str)
    implicit def fromRequestResponse(f: (HttpServletRequest, HttpServletResponse) => Unit): HttpResp = ReqToRespResp(f)
    implicit def fromFile(path: Path): HttpResp = PathResp(path)
    implicit def fromInputStream(in: InputStream): HttpResp = InputStreamResp(in)
  }
}
