package manifests.passengers

import drt.shared.SDateLike
import drt.shared.SplitRatiosNs.SplitSources
import manifests.UniqueArrivalKey
import passengersplits.core.PassengerTypeCalculatorValues.{CountryCodes, DocType}
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
  def apply(manifest: VoyageManifest): BestAvailableManifest = {

    val uniquePax: List[PassengerInfoJson] = if (manifest.PassengerList.exists(_.PassengerIdentifier.exists(_ != "")))
      manifest.PassengerList.collect {
        case p@PassengerInfoJson(_, _, _, _, _, _, _, _, Some(id)) if id != "" => p
      }
        .map { passengerInfo =>
          passengerInfo.PassengerIdentifier -> passengerInfo
        }
        .toMap
        .values
        .toList
    else
      manifest.PassengerList

    BestAvailableManifest(
      SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages,
      manifest.ArrivalPortCode,
      manifest.DeparturePortCode,
      manifest.VoyageNumber,
      manifest.CarrierCode,
      manifest.scheduleArrivalDateTime.getOrElse(SDate.now()),
      uniquePax.map(p => ManifestPassengerProfile(p, manifest.ArrivalPortCode))
    )
  }

  def apply(source: String,
            uniqueArrivalKey: UniqueArrivalKey,
            passengerList: List[ManifestPassengerProfile]): BestAvailableManifest = BestAvailableManifest(
    source,
    uniqueArrivalKey.arrivalPort,
    uniqueArrivalKey.departurePort,
    uniqueArrivalKey.voyageNumber,
    "",
    uniqueArrivalKey.scheduled,
    passengerList)
}

case class ManifestPassengerProfile(nationality: String,
                                    documentType: Option[String],
                                    age: Option[Int],
                                    inTransit: Option[Boolean]
                                   )

object ManifestPassengerProfile {
  def apply(pij: PassengerInfoJson, portCode: String): ManifestPassengerProfile = {
    val nationality = pij.NationalityCountryCode.getOrElse("")
    val documentType: Option[String] = if (nationality == CountryCodes.UK)
      Option(DocType.Passport)
    else
      pij.DocumentType.map(DocType(_))
    val maybeAge = pij.Age.flatMap(a => Try(a.toInt).toOption)
    val maybeInTransit = Option(pij.InTransitFlag == "Y" || pij.DisembarkationPortCode.exists(_ != portCode))
    ManifestPassengerProfile(nationality, documentType, maybeAge, maybeInTransit)
  }
}
