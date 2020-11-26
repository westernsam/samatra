package com.springer.samatra.routing

import javax.servlet.http._
import scala.collection.mutable.ListBuffer
import scala.util.matching.Regex

object Routings {

  sealed trait HttpMethod
  case object HEAD extends HttpMethod
  case object GET extends HttpMethod
  case object POST extends HttpMethod
  case object PUT extends HttpMethod
  case object DELETE extends HttpMethod
  case object OPTIONS extends HttpMethod
  case object TRACE extends HttpMethod
  case object CONNECT extends HttpMethod

  trait HttpResp {
    def process(req: HttpServletRequest, resp: HttpServletResponse): Unit
  }

  class ControllerServlet(controller: Routes) extends HttpServlet {
    override def service(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
      val request: Request = Request(req)

      val matchingRoute: Route = controller.matching(req, resp) match {
        case Right(route) => route
        case Left(matchingOnDifferentMethod) => new DefaultRoute(matchingOnDifferentMethod)
      }

      matchingRoute.write(request.copy(params = matchingRoute.matches(request).get), resp)
    }
  }

  object Routes {
    def apply(controllers: Routes*) = new ControllerServlet(new AggregateRoutes(controllers: _*))
  }

  trait Routes {
    def routes: Seq[Route]

    def matching(req: HttpServletRequest, resp: HttpServletResponse): Either[Seq[Route], Route] = {
      val matchingRoutes: Seq[Route] = routes.filter(_.matches(Request(req)).isDefined)
      //find first
      matchingRoutes.find(_.method.toString == req.getMethod) match {
        case Some(r) => Right(r)
        case None => Left(matchingRoutes)
      }
    }
  }

  class AggregateRoutes(val controllers: Routes*) extends Routes {
    override def routes: Seq[Route] = controllers.flatMap(_.routes)
  }

  abstract class Controller extends Routes {
    private val routesBuffer: ListBuffer[Route] = new ListBuffer[Route]()

    override def routes: Seq[Route] = routesBuffer.toSeq

    protected def get(path: String)(body: Request => HttpResp): Unit = {
      getWithoutHead(path)(body)
      routesBuffer.append(PathParamsRoute(HEAD, path, NoBody(body)))
    }

    protected def get(pattern: Regex)(body: Request => HttpResp): Unit = {
      getWithoutHead(pattern)(body)
      routesBuffer.append(RegexRoute(HEAD, pattern, NoBody(body)))
    }

    //noinspection AccessorLikeMethodIsUnit
    protected def getWithoutHead(path: String)(body: Request => HttpResp): Unit = routesBuffer.append(PathParamsRoute(GET, path, body))

    //noinspection AccessorLikeMethodIsUnit
    protected def getWithoutHead(pattern: Regex)(body: Request => HttpResp): Unit = routesBuffer.append(RegexRoute(GET, pattern, body))
    protected def post(path: String)(fromRequest: Request => HttpResp): Unit = routesBuffer.append(PathParamsRoute(POST, path, fromRequest))
    protected def post(pattern: Regex)(fromRequest: Request => HttpResp): Unit = routesBuffer.append(RegexRoute(POST, pattern, fromRequest))
    protected def put(path: String)(fromRequest: Request => HttpResp): Unit = routesBuffer.append(PathParamsRoute(PUT, path, fromRequest))
    protected def put(pattern: Regex)(fromRequest: Request => HttpResp): Unit = routesBuffer.append(RegexRoute(PUT, pattern, fromRequest))
    protected def head(path: String)(fromRequest: Request => HeadersOnly): Unit = routesBuffer.append(PathParamsRoute(HEAD, path, fromRequest))
    protected def head(pattern: Regex)(fromRequest: Request => HeadersOnly): Unit = routesBuffer.append(RegexRoute(HEAD, pattern, fromRequest))
    protected def delete(path: String)(fromRequest: Request => HttpResp): Unit = routesBuffer.append(PathParamsRoute(DELETE, path, fromRequest))
    protected def delete(pattern: Regex)(fromRequest: Request => HttpResp): Unit = routesBuffer.append(RegexRoute(DELETE, pattern, fromRequest))
    protected def options(path: String)(fromRequest: Request => HttpResp): Unit = routesBuffer.append(PathParamsRoute(OPTIONS, path, fromRequest))
    protected def options(pattern: Regex)(fromRequest: Request => HttpResp): Unit = routesBuffer.append(RegexRoute(OPTIONS, pattern, fromRequest))
    protected def trace(path: String)(fromRequest: Request => HttpResp): Unit = routesBuffer.append(PathParamsRoute(TRACE, path, fromRequest))
    protected def trace(pattern: Regex)(fromRequest: Request => HttpResp): Unit = routesBuffer.append(RegexRoute(TRACE, pattern, fromRequest))
    protected def connect(path: String)(fromRequest: Request => HttpResp): Unit = routesBuffer.append(PathParamsRoute(CONNECT, path, fromRequest))
    protected def connect(pattern: Regex)(fromRequest: Request => HttpResp): Unit = routesBuffer.append(RegexRoute(CONNECT, pattern, fromRequest))
  }

  class HeadersOnly(val headers: (String, String)*) extends HttpResp {
    override def process(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
      headers.foreach(t => resp.setHeader(t._1, t._2))
    }
  }

  object HeadersOnly {
    def apply(headers: (String, String)*): HeadersOnly = new HeadersOnly(headers: _*)
  }

  trait Route {
    def method: HttpMethod

    def matches(req: Request): Option[collection.Map[String, String]]

    def write(req: Request, resp: HttpServletResponse): Unit
  }

  sealed class DefaultRoute(routes: Seq[Route]) extends Route {
    override def method: HttpMethod = throw new IllegalStateException

    override def matches(req: Request): Option[collection.Map[String, String]] = Some(Map())

    override def write(req: Request, resp: HttpServletResponse): Unit = routes.toList match {
      case Nil => resp.sendError(404)
      case _ =>
        resp.setHeader("Allow", routes.map(_.method).toSet.mkString(", "))
        resp.sendError(405)
    }
  }

  sealed abstract class WritingRoute(method: HttpMethod, response: Request => HttpResp) extends Route {
    override def write(req: Request, resp: HttpServletResponse): Unit = {
      response(req).process(req.underlying, resp)
    }
  }

  sealed case class RegexRoute(method: HttpMethod, pattern: Regex, response: Request => HttpResp) extends WritingRoute(method, response) {
    override def matches(req: Request): Option[Map[String, String]] =
      pattern.unapplySeq(req.relativePath).map(_.zipWithIndex.map { case (k, v) => v.toString -> k }.toMap)

    override def toString: String = {
      s"${method.toString.padTo(4, ' ')} ${pattern.toString.padTo(32, ' ')}"
    }
  }

  sealed case class PathParamsRoute(method: HttpMethod, path: String, response: Request => HttpResp) extends WritingRoute(method, response) {
    override def matches(req: Request): Option[collection.Map[String, String]] = {
      val actual: Array[String] = req.relativePath.split("/")
      val pattern: Array[String] = path.split("/")

      if (actual.length != pattern.length) None
      else pattern.zip(actual).foldLeft[Option[Map[String, String]]](Some(Map.empty)) {
        case (r, (left, right)) => r match {
          case None => None
          case Some(map) =>
            if (left == right) r
            else if (left.startsWith(":")) Some(map + (left.substring(1) -> right))
            else None
        }
      }
    }

    override def toString: String =
      s"${method.toString.padTo(4, ' ')} ${path.padTo(32, ' ')}"
  }
}
