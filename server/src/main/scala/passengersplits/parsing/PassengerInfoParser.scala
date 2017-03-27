package passengersplits.parsing

import drt.shared.SDateLike
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import org.joda.time.DateTime
import services.SDate.JodaSDate

import scala.util.Try

object PassengerInfoParser {

  case class PassengerInfo(DocumentType: Option[String],
                           DocumentIssuingCountryCode: String, Age: Option[Int] = None)

  case class PassengerInfoJson(DocumentType: Option[String],
                               DocumentIssuingCountryCode: String,
                               EEAFlag: String,
                               Age: Option[String] = None) {
    def toPassengerInfo = PassengerInfo(DocumentType, DocumentIssuingCountryCode, Age match {
      case Some(age) => Try(age.toInt).toOption
      case None => None
    })
  }

  object EventCodes {
    val DoorsClosed = "DC"
    val CheckIn = "CI"
  }

  case class VoyagePassengerInfo(EventCode: String,
                                 ArrivalPortCode: String,
                                 DeparturePortCode: String,
                                 VoyageNumber: String,
                                 CarrierCode: String,
                                 ScheduledDateOfArrival: String,
                                 ScheduledTimeOfArrival: String,
                                 PassengerList: List[PassengerInfoJson]) {
    def flightCode: String = CarrierCode + VoyageNumber

    def scheduleArrivalDateTime: Option[SDateLike] = {
      Try(DateTime.parse(scheduleDateTimeString)).toOption.map(JodaSDate(_))
    }

    def passengerInfos: Seq[PassengerInfo] = PassengerList.map(_.toPassengerInfo)

    private def scheduleDateTimeString: String = s"${ScheduledDateOfArrival}T${ScheduledTimeOfArrival}Z"

    def summary: String = s"${DeparturePortCode}->${ArrivalPortCode}/${CarrierCode}${VoyageNumber}@${scheduleDateTimeString}"
  }

  object FlightPassengerInfoProtocol extends DefaultJsonProtocol {
    implicit val passengerInfoConverter = jsonFormat(PassengerInfoJson, "DocumentType",
      "DocumentIssuingCountryCode", "NationalityCountryEEAFlag", "Age")
    implicit val passengerInfoResponseConverter: RootJsonFormat[VoyagePassengerInfo] = jsonFormat8(VoyagePassengerInfo)
  }

}
