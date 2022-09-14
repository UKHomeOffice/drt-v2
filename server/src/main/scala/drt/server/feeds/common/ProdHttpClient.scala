package drt.server.feeds.common

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}

import scala.concurrent.Future

trait HttpClient {
  def sendRequest(httpRequest: HttpRequest): Future[HttpResponse]
}

case class ProdHttpClient()(implicit system: ActorSystem) extends HttpClient {
  def sendRequest(httpRequest: HttpRequest): Future[HttpResponse] = Http().singleRequest(httpRequest)
}
