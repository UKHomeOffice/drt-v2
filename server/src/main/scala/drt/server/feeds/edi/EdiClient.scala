package drt.server.feeds.edi

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import drt.server.feeds.common.HttpClient

import scala.concurrent.Future

case class EdiClient(endpoint: String, subscriberId: String, httpClient: HttpClient) {

  def makeRequest(startDate: String, endDate: String)(implicit system: ActorSystem): Future[HttpResponse] = {
    val httpRequest = HttpRequest(HttpMethods.POST, endpoint)
      .withHeaders(RawHeader("Content-Type", "application/json"))
      .withHeaders(RawHeader("Ocp-Apim-Subscription-Key", subscriberId))
      .withEntity(HttpEntity(ContentTypes.`application/json`, s"""{"startDate":"$startDate","endDate":"$endDate"}"""))
    httpClient.sendRequest(httpRequest)
  }
}