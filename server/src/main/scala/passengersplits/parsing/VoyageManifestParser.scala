package passengersplits.parsing

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{ArrivalKey, EventType, PortCode, SDateLike}
import org.joda.time.DateTime
import services.SDate.JodaSDate
import spray.json.{DefaultJsonProtocol, JsString, JsValue, RootJsonFormat}

import scala.util.Try

object VoyageManifestParser {
  def parseVoyagePassengerInfo(content: String): Try[VoyageManifest] = {
    import FlightPassengerInfoProtocol._
    import spray.json._
    Try(content.parseJson.convertTo[VoyageManifest])
  }

  case class PassengerInfo(DocumentType: Option[String],
                           DocumentIssuingCountryCode: String, Age: Option[Int] = None)

  case class PassengerInfoJson(DocumentType: Option[String],
                               DocumentIssuingCountryCode: String,
                               EEAFlag: String,
                               Age: Option[String] = None,
                               DisembarkationPortCode: Option[String],
                               InTransitFlag: String = "N",
                               DisembarkationPortCountryCode: Option[String] = None,
                               NationalityCountryCode: Option[String] = None,
                               PassengerIdentifier: Option[String]
                              ) {
    def toPassengerInfo = PassengerInfo(DocumentType, DocumentIssuingCountryCode, Age match {
      case Some(age) => Try(age.toInt).toOption
      case None => None
    })
  }

  case class VoyageManifests(manifests: Set[VoyageManifest])

  case class VoyageManifest(EventCode: EventType,
                            ArrivalPortCode: PortCode,
                            DeparturePortCode: PortCode,
                            VoyageNumber: String,
                            CarrierCode: String,
                            ScheduledDateOfArrival: String,
                            ScheduledTimeOfArrival: String,
                            PassengerList: List[PassengerInfoJson]) {
    def flightCode: String = CarrierCode + VoyageNumber

    def scheduleArrivalDateTime: Option[SDateLike] = Try(DateTime.parse(scheduleDateTimeString)).toOption.map(JodaSDate)

    def passengerInfos: Seq[PassengerInfo] = PassengerList.map(_.toPassengerInfo)

    def scheduleDateTimeString: String = s"${ScheduledDateOfArrival}T${ScheduledTimeOfArrival}Z"

    def millis: MillisSinceEpoch = scheduleArrivalDateTime.map(_.millisSinceEpoch).getOrElse(0L)

    def summary: String = s"$DeparturePortCode->$ArrivalPortCode/$CarrierCode$VoyageNumber@$scheduleDateTimeString"

    def key: Int = s"$VoyageNumber-${scheduleArrivalDateTime.map(_.millisSinceEpoch).getOrElse(0L)}".hashCode

    def arrivalKey = ArrivalKey(DeparturePortCode, VoyageNumber, millis)
  }

  object FlightPassengerInfoProtocol extends DefaultJsonProtocol {
    implicit val passengerInfoConverter: RootJsonFormat[PassengerInfoJson] = jsonFormat(PassengerInfoJson,
      "DocumentType",
      "DocumentIssuingCountryCode",
      "NationalityCountryEEAFlag",
      "Age",
      "DisembarkationPortCode",
      "InTransitFlag",
      "DisembarkationPortCountryCode",
      "NationalityCountryCode",
      "PassengerIdentifier"
    )
    implicit object EventTypeJsonFormat extends RootJsonFormat[EventType] {
      def write(c: EventType) = JsString(c.toString)

      def read(value: JsValue): EventType = value match {
        case str: JsString => EventType(str.value)
      }
    }
    implicit object PortCodeJsonFormat extends RootJsonFormat[PortCode] {
      def write(c: PortCode) = JsString(c.toString)

      def read(value: JsValue): PortCode = value match {
        case str: JsString => PortCode(str.value)
      }
    }
    implicit val passengerInfoResponseConverter: RootJsonFormat[VoyageManifest] = jsonFormat8(VoyageManifest)
  }

}
