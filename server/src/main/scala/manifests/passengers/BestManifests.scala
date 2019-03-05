package manifests.passengers

import drt.shared.SDateLike
import drt.shared.SplitRatiosNs.SplitSources
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest}
import services.SDate

import scala.util.Try

case class BestAvailableManifest(source: String,
                                 arrivalPortCode: String,
                                 departurePortCode: String,
                                 voyageNumber: String,
                                 carrierCode: String,
                                 scheduled: SDateLike,
                                 passengerList: List[ManifestPassengerProfile])

object BestAvailableManifest {
  def apply(manifest: VoyageManifest, portCode: String): BestAvailableManifest = BestAvailableManifest(
    SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages,
    manifest.ArrivalPortCode,
    manifest.DeparturePortCode,
    manifest.VoyageNumber,
    manifest.CarrierCode,
    manifest.scheduleArrivalDateTime.getOrElse(SDate.now()),
    manifest.PassengerList.map(p => ManifestPassengerProfile(p, portCode)))
}

case class ManifestPassengerProfile(nationality: String,
                                    documentType: Option[String],
                                    age: Option[Int],
                                    inTransit: Option[Boolean])

object ManifestPassengerProfile {
  def apply(pij: PassengerInfoJson, portCode: String): ManifestPassengerProfile = {
    val nationality = pij.NationalityCountryCode.getOrElse("")
    val documentType: Option[String] = pij.DocumentType
    val maybeAge = pij.Age.flatMap(a => Try(a.toInt).toOption)
    val maybeInTransit = Option(pij.InTransitFlag == "Y" || pij.DisembarkationPortCode.exists(_ != portCode))
    ManifestPassengerProfile(nationality, documentType, maybeAge, maybeInTransit)
  }
}
