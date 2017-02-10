package passengersplits.parsing

import spray.http.DateTime
import spray.json.{RootJsonFormat, DefaultJsonProtocol}

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
                                 VoyageNumber: String,
                                 CarrierCode: String,
                                 ScheduledDateOfArrival: String,
                                 ScheduledTimeOfArrival: String,
                                 PassengerList: List[PassengerInfoJson]) {
    def flightCode: String = CarrierCode + VoyageNumber
    def scheduleArrivalDateTime: Option[DateTime] = {
      DateTime.fromIsoDateTimeString(scheduleDateTimeString)
    }
    def passengerInfos: Seq[PassengerInfo] = PassengerList.map(_.toPassengerInfo)

    private def scheduleDateTimeString: String = s"${ScheduledDateOfArrival}T${ScheduledTimeOfArrival}"
    def summary: String = s"${ArrivalPortCode}/${CarrierCode}${VoyageNumber}@${scheduleDateTimeString}"
  }

  object FlightPassengerInfoProtocol extends DefaultJsonProtocol {
    implicit val passengerInfoConverter = jsonFormat(PassengerInfoJson, "DocumentType",
      "DocumentIssuingCountryCode", "NationalityCountryEEAFlag", "Age")
    implicit val passengerInfoResponseConverter: RootJsonFormat[VoyagePassengerInfo] = jsonFormat7(VoyagePassengerInfo)
  }

}
