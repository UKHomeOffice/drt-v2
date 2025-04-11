package drt.server.feeds.common

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}

import scala.concurrent.Future

trait HttpClient {
  def sendRequest(httpRequest: HttpRequest): Future[HttpResponse]
}

case class ProdHttpClient()(implicit system: ActorSystem) extends HttpClient {
  def sendRequest(httpRequest: HttpRequest): Future[HttpResponse] = Http().singleRequest(httpRequest)
}
