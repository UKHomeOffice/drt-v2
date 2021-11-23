package drt.server.feeds.common

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}

import scala.concurrent.{ExecutionContext, Future}

trait HttpClient {
  def sendRequest(httpRequest: HttpRequest)
                 (implicit system: ActorSystem): Future[HttpResponse]
}

class ProdHttpClient extends HttpClient {
  def sendRequest(httpRequest: HttpRequest)
                 (implicit system: ActorSystem): Future[HttpResponse] =
    Http().singleRequest(httpRequest)
}
