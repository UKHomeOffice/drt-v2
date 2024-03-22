package drt.server.feeds.lcy

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{BasicHttpCredentials, ModeledCustomHeader, ModeledCustomHeaderCompanion}
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.Materializer
import drt.server.feeds.common.HttpClient
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try
import scala.xml.NodeSeq

case class LCYClient(httpClient: HttpClient, lcyLiveFeedUser: String, soapEndPoint: String, username: String, password: String) extends ProdLcyClientSupport {

  def makeRequest(endpoint: String, headers: List[HttpHeader], postXML: String)(implicit system: ActorSystem): Future[HttpResponse] = {
    val httpRequest = HttpRequest(HttpMethods.POST, endpoint, headers, HttpEntity(ContentTypes.`text/xml(UTF-8)`, postXML)).addCredentials(BasicHttpCredentials(username, password))
    httpClient.sendRequest(httpRequest)
  }
}

final class SoapActionHeader(action: String) extends ModeledCustomHeader[SoapActionHeader] {
  override def renderInRequests = true

  override def renderInResponses = false

  override val companion: ModeledCustomHeaderCompanion[SoapActionHeader] = SoapActionHeader

  override def value: String = action
}

object SoapActionHeader extends ModeledCustomHeaderCompanion[SoapActionHeader] {
  override val name = "SOAPAction"

  override def parse(value: String): Try[SoapActionHeader] = Try(new SoapActionHeader(value))
}

trait LcyClientSupport {
  def initialFlights(implicit actorSystem: ActorSystem, materializer: Materializer): Future[ArrivalsFeedResponse]

  def updateFlights(implicit actorSystem: ActorSystem, materializer: Materializer): Future[ArrivalsFeedResponse]
}

trait ProdLcyClientSupport extends LcyClientSupport with ScalaXmlSupport {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def lcyLiveFeedUser: String

  def soapEndPoint: String

  def username: String

  def password: String

  override def initialFlights(implicit actorSystem: ActorSystem, materializer: Materializer): Future[ArrivalsFeedResponse] = {
    log.info(s"Making initial Live Feed Request")
    sendXMLRequest(fullRefreshXml(lcyLiveFeedUser))
  }

  override def updateFlights(implicit actorSystem: ActorSystem, materializer: Materializer): Future[ArrivalsFeedResponse] = {
    log.info(s"Making update Feed Request")
    sendXMLRequest(updateXml()(lcyLiveFeedUser))
  }

  def sendXMLRequest(postXml: String)(implicit actorSystem: ActorSystem, materializer: Materializer): Future[ArrivalsFeedResponse] = {

    implicit val xmlToResUM: Unmarshaller[NodeSeq, LCYFlightsResponse] = LCYFlightTransform.unmarshaller
    implicit val resToLCYResUM: Unmarshaller[HttpResponse, LCYFlightsResponse] = LCYFlightTransform.responseToAUnmarshaller

    val headers: List[HttpHeader] = List(
      SoapActionHeader(""),
    )

    makeRequest(soapEndPoint, headers, postXml)
      .map(res => {
        log.info(s"Got a response from LCY ${res.status}")
        val lcyResponse = Unmarshal[HttpResponse](res).to[LCYFlightsResponse]

        lcyResponse.map {
          case s: LCYFlightsResponseSuccess =>
            ArrivalsFeedSuccess(s.flights.map(fs => LCYFlightTransform.lcyFlightToArrival(fs)))

          case f: LCYFlightsResponseFailure =>
            ArrivalsFeedFailure(f.message)
        }
      })
      .flatMap(identity)
      .recoverWith {
        case f =>
          log.error(s"Failed to get LCY Live Feed", f)
          Future(ArrivalsFeedFailure(f.getMessage))
      }
  }

  def fullRefreshXml: String => String = postXMLTemplate(fullRefresh = "1")

  def updateXml(): String => String = postXMLTemplate(fullRefresh = "0")

  def postXMLTemplate(fullRefresh: String)(username: String): String = {
    s"""<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns="http://www.airport2020.com/RequestAIDX/">
       |    <soapenv:Header/>
       |    <soapenv:Body>
       |        <ns:userID>$username</ns:userID>
       |        <ns:fullRefresh>$fullRefresh</ns:fullRefresh>
       |    </soapenv:Body>
       |</soapenv:Envelope>""".stripMargin
  }

  def makeRequest(endpoint: String, headers: List[HttpHeader], postXML: String)(implicit system: ActorSystem): Future[HttpResponse]

}

