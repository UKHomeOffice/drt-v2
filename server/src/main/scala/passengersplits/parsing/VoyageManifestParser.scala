package passengersplits.parsing

import spray.json.{DefaultJsonProtocol, JsNumber, JsString, JsValue, RootJsonFormat}
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.EventTypes.InvalidEventType
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.models._
import uk.gov.homeoffice.drt.ports.{PaxAge, PortCode}

import scala.util.{Success, Try}


object VoyageManifestParser {
  def parseVoyagePassengerInfo(content: String): Try[VoyageManifest] = {
    import FlightPassengerInfoProtocol._
    import spray.json._
    Try(content.parseJson.convertTo[VoyageManifest])
  }

  object FlightPassengerInfoProtocol extends DefaultJsonProtocol {

    implicit object DocumentTypeJsonFormat extends RootJsonFormat[DocumentType] {
      def write(c: DocumentType): JsString = JsString(c.toString)

      def read(value: JsValue): DocumentType = value match {
        case str: JsString => DocumentType(str.value)
      }
    }

    implicit object NationalityJsonFormat extends RootJsonFormat[Nationality] {
      def write(c: Nationality): JsString = JsString(c.toString)

      def read(value: JsValue): Nationality = value match {
        case str: JsString => Nationality(str.value)
      }
    }

    implicit object PortCodeJsonFormat extends RootJsonFormat[PortCode] {
      def write(c: PortCode): JsString = JsString(c.toString)

      def read(value: JsValue): PortCode = value match {
        case str: JsString => PortCode(str.value)
        case _ => PortCode("")
      }
    }

    implicit object EeaFlagJsonFormat extends RootJsonFormat[EeaFlag] {
      def write(c: EeaFlag): JsString = JsString(c.toString)

      def read(value: JsValue): EeaFlag = value match {
        case str: JsString => EeaFlag(str.value)
        case _ => EeaFlag("")
      }
    }

    implicit object PaxAgeJsonFormat extends RootJsonFormat[Option[PaxAge]] {
      def write(c: Option[PaxAge]): JsNumber = c match {
        case Some(PaxAge(years)) => JsNumber(years)
        case None => JsNumber(-1)
      }

      def read(value: JsValue): Option[PaxAge] = Try(value.convertTo[String].toInt) match {
        case Success(num) if num > 0 => Option(PaxAge(num))
        case _ => None
      }
    }

    implicit object InTransitJsonFormat extends RootJsonFormat[InTransit] {
      def write(c: InTransit): JsString = JsString(if (c.isInTransit) "Y" else "N")

      def read(value: JsValue): InTransit = value match {
        case str: JsString => InTransit(str.value)
        case _ => InTransit(false)
      }
    }

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
      def write(c: EventType): JsString = JsString(c.toString)

      def read(value: JsValue): EventType = value match {
        case str: JsString => EventType(str.value)
        case _ => InvalidEventType
      }
    }

    implicit object CarrierCodeJsonFormat extends RootJsonFormat[CarrierCode] {
      def write(c: CarrierCode): JsString = JsString(c.toString)

      def read(value: JsValue): CarrierCode = value match {
        case str: JsString => CarrierCode(str.value)
      }
    }

    implicit object VoyageNumberJsonFormat extends RootJsonFormat[VoyageNumberLike] {
      def write(c: VoyageNumberLike): JsString = JsString(c.toString)

      def read(value: JsValue): VoyageNumberLike = value match {
        case str: JsString => VoyageNumber(str.value)
        case _ => InvalidVoyageNumber
      }
    }

    implicit object ManifestDateOfArrivalJsonFormat extends RootJsonFormat[ManifestDateOfArrival] {
      def write(c: ManifestDateOfArrival): JsString = JsString(c.toString)

      def read(value: JsValue): ManifestDateOfArrival = value match {
        case str: JsString => ManifestDateOfArrival(str.value)
      }
    }

    implicit object ManifestTimeOfArrivalJsonFormat extends RootJsonFormat[ManifestTimeOfArrival] {
      def write(c: ManifestTimeOfArrival): JsString = JsString(c.toString)

      def read(value: JsValue): ManifestTimeOfArrival = value match {
        case str: JsString => ManifestTimeOfArrival(str.value)
      }
    }

    implicit val passengerInfoResponseConverter: RootJsonFormat[VoyageManifest] =
      jsonFormat(
      VoyageManifest,
      "EventCode",
      "ArrivalPortCode",
      "DeparturePortCode",
      "VoyageNumber",
      "CarrierCode",
      "ScheduledDateOfArrival",
      "ScheduledTimeOfArrival",
      "PassengerList"
    )
  }

}
