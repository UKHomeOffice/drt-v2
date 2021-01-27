package passengersplits.parsing

import drt.shared.EventTypes.InvalidEventType
import drt.shared.SplitRatiosNs.SplitSources.{AdvPaxInfo, ApiSplitsWithHistoricalEGateAndFTPercentages}
import drt.shared._
import manifests.passengers.{ManifestLike, ManifestPassengerProfile}
import org.joda.time.DateTime
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import services.SDate
import services.SDate.JodaSDate
import spray.json.{DefaultJsonProtocol, JsNumber, JsString, JsValue, RootJsonFormat}

import scala.util.{Failure, Success, Try}

object VoyageManifestParser {
  def parseVoyagePassengerInfo(content: String): Try[VoyageManifest] = {
    import FlightPassengerInfoProtocol._
    import spray.json._
    Try(content.parseJson.convertTo[VoyageManifest])
  }

  case class InTransit(isInTransit: Boolean) {
    override def toString: String = if (isInTransit) "Y" else "N"
  }

  object InTransit {
    def apply(inTransitString: String): InTransit = InTransit(inTransitString == "Y")
  }

  case class EeaFlag(value: String)


  case class PassengerInfoJson(DocumentType: Option[DocumentType],
                               DocumentIssuingCountryCode: Nationality,
                               EEAFlag: EeaFlag,
                               Age: Option[PaxAge] = None,
                               DisembarkationPortCode: Option[PortCode],
                               InTransitFlag: InTransit = InTransit(false),
                               DisembarkationPortCountryCode: Option[Nationality] = None,
                               NationalityCountryCode: Option[Nationality] = None,
                               PassengerIdentifier: Option[String]
                              )

  case class VoyageManifests(manifests: Set[VoyageManifest]) {

    def toMap: Map[ArrivalKey, VoyageManifest] = manifests.collect {
      case vm if vm.maybeKey.isDefined =>
        vm.maybeKey.get -> vm
    }.toMap

    def ++(other: VoyageManifests): VoyageManifests = VoyageManifests(manifests ++ other.manifests)
  }

  object VoyageManifests {
    def empty: VoyageManifests = VoyageManifests(Set())
  }

  case class ManifestDateOfArrival(date: String) {
    override def toString: String = date
  }

  case class ManifestTimeOfArrival(time: String) {
    override def toString: String = time
  }

  case class VoyageManifest(EventCode: EventType,
                            ArrivalPortCode: PortCode,
                            DeparturePortCode: PortCode,
                            VoyageNumber: VoyageNumberLike,
                            CarrierCode: CarrierCode,
                            ScheduledDateOfArrival: ManifestDateOfArrival,
                            ScheduledTimeOfArrival: ManifestTimeOfArrival,
                            PassengerList: List[PassengerInfoJson]) extends ManifestLike {
    def flightCode: String = CarrierCode.code + VoyageNumber

    def scheduleArrivalDateTime: Option[SDateLike] = Try(DateTime.parse(scheduleDateTimeString)).toOption.map(JodaSDate)

    def scheduleDateTimeString: String = s"${ScheduledDateOfArrival}T${ScheduledTimeOfArrival}Z"

    def maybeKey: Option[ArrivalKey] = (scheduleArrivalDateTime, VoyageNumber) match {
      case (Some(scheduled), vn: VoyageNumber) =>
        Option(ArrivalKey(DeparturePortCode, vn, scheduled.millisSinceEpoch))
      case _ => None
    }

    override val source: SplitRatiosNs.SplitSource = ApiSplitsWithHistoricalEGateAndFTPercentages
    override val scheduled: SDateLike = scheduleArrivalDateTime.getOrElse(SDate(0))
    override val arrivalPortCode: PortCode = ArrivalPortCode
    override val departurePortCode: PortCode = DeparturePortCode
    override val voyageNumber: VoyageNumberLike = VoyageNumber
    override val carrierCode: CarrierCode = CarrierCode
    override val passengers: List[ManifestPassengerProfile] = PassengerList.map(ManifestPassengerProfile(_, arrivalPortCode))
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
        case unexpected => InvalidVoyageNumber(new Exception(s"unexpected json value: $unexpected"))
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
