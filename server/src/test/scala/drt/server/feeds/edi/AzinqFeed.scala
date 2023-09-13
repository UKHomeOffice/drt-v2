package drt.server.feeds.edi

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.specs2.mutable.Specification
import spray.json.{JsObject, JsValue, RootJsonFormat}
import uk.gov.homeoffice.drt.arrivals.{Arrival, CarrierCode}

import scala.concurrent.{ExecutionContext, Future}

object AzinqArrivalEdiJsonFormats {
  import spray.json.DefaultJsonProtocol._
  implicit object AzinqArrivalJsonFormat extends RootJsonFormat[Arrival] {
    def write(c: Arrival): JsValue = throw new Exception("Serialisation not supported")

    def read(value: JsValue): Arrival = value match {
      case str: JsObject =>
        val number = str.fields("FlightNumber").convertTo[String].toInt
        val carrierCode = CarrierCode(str.fields("AirlineIATA").convertTo[String])
        val scheduled = str.fields("ScheduledDateTime").convertTo[String]
        val estimated = str.fields("EstimatedDateTime").convertTo[String]
        val baggageId = str.fields("CarouselCode").convertTo[String]

        Arrival()
    }
  }
}

object AzinqFeed {
  val url = "https://edi.azinqairportapi.com/v1/getdata/flights"
  def apply(username: String, password: String, token: String, httpRequest: HttpRequest => Future[HttpResponse])
           (implicit ec: ExecutionContext): () => Future[Seq[Arrival]] = {
    val request = HttpRequest(uri = url, headers = List(
      "token" -> token,
      "username" -> username,
      "password" -> password
    ))

    () => httpRequest(request).map(_ => Seq())
  }
}

class AzinqFeed extends Specification {
  ""
}
