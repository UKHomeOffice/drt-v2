package passengersplits.parsing

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SDateLike
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import org.joda.time.DateTime
import services.SDate.JodaSDate

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

  case class VoyageManifest(EventCode: String,
                            ArrivalPortCode: String,
                            DeparturePortCode: String,
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
  }

  case class BestAvailableManifest(source: String,
                                   arrivalPortCode: String,
                                   departurePortCode: String,
                                   voyageNumber: String,
                                   carrierCode: String,
                                   scheduled: SDateLike,
                                   passengerList: List[ManifestPassengerProfile])

  case class ManifestPassengerProfile(nationality: String,
                                      documentType: Option[String],
                                      age: Option[Int],
                                      inTransit: Option[Boolean])

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
    implicit val passengerInfoResponseConverter: RootJsonFormat[VoyageManifest] = jsonFormat8(VoyageManifest)
  }

}
