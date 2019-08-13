package services

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import drt.shared.SDateLike
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class OOHChecker(bankHolidayClient: BankHolidayApiClient) {

  val startOfDay = "09:00"
  val endOfDay = "17:30"

  def isOOH(localTime: SDateLike): Future[Boolean] = {

    val time = localTime.toLocalDateTimeString().split(" ").last

    bankHolidayClient.isEnglandAndWalesBankHoliday(localTime).map { isBankHoliday =>
      isBankHoliday || time < startOfDay || time > endOfDay || isWeekend(localTime)
    }
  }

  def isWeekend(localTime: SDateLike) = localTime.getDayOfWeek >= 6

}

case class BankHolidayApiClient(uri: String = "https://www.gov.uk/bank-holidays.json")(implicit system: ActorSystem) {

  import BankHolidayParserProtocol._

  def getHolidays(): Future[Map[String, BankHolidayDivision]] = {
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    sendAndReceive(HttpRequest(HttpMethods.GET, Uri(uri)))
      .map(res => Unmarshal[HttpResponse](res).to[Map[String, BankHolidayDivision]])
      .flatMap(identity)
  }

  def sendAndReceive: HttpRequest => Future[HttpResponse] = request => Http()(system).singleRequest(request)

  def isEnglandAndWalesBankHoliday(localTime: SDateLike): Future[Boolean] = getHolidays()
    .map {
      hols =>
        hols.get("england-and-wales")
          .map(_.events.filter(_.date == localTime.toISODateOnly))
          .getOrElse(Nil)
          .nonEmpty
    }

}

case class BankHoliday(title: String, date: String, notes: String, bunting: Boolean)

case class BankHolidayDivision(division: String, events: List[BankHoliday])

object BankHolidayParserProtocol extends DefaultJsonProtocol with SprayJsonSupport {
  implicit val bankHolidayParserFormat: RootJsonFormat[BankHoliday] = jsonFormat4(BankHoliday)
  implicit val bankHolidayDivisionParserFormat: RootJsonFormat[BankHolidayDivision] = jsonFormat2(BankHolidayDivision)
}
