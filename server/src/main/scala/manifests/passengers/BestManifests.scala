package manifests.passengers

import drt.shared.SplitRatiosNs.{SplitSource, SplitSources}
import drt.shared._
import manifests.UniqueArrivalKey
import passengersplits.core.PassengerTypeCalculatorValues.{CountryCodes, DocumentType}
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, PaxAge, VoyageManifest}
import services.SDate

case class BestAvailableManifest(source: SplitSource,
                                 arrivalPortCode: PortCode,
                                 departurePortCode: PortCode,
                                 voyageNumber: VoyageNumberLike,
                                 carrierCode: CarrierCode,
                                 scheduled: SDateLike,
                                 passengerList: List[ManifestPassengerProfile])

object BestAvailableManifest {
  def apply(manifest: VoyageManifest): BestAvailableManifest = BestAvailableManifest(
    SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages,
    manifest.ArrivalPortCode,
    manifest.DeparturePortCode,
    manifest.VoyageNumber,
    manifest.CarrierCode,
    manifest.scheduleArrivalDateTime.getOrElse(SDate.now()),
    manifest.PassengerList.map(p => ManifestPassengerProfile(p, manifest.ArrivalPortCode)))

  def apply(source: SplitSource,
            uniqueArrivalKey: UniqueArrivalKey,
            passengerList: List[ManifestPassengerProfile]): BestAvailableManifest = BestAvailableManifest(
    source,
    uniqueArrivalKey.arrivalPort,
    uniqueArrivalKey.departurePort,
    uniqueArrivalKey.voyageNumber,
    CarrierCode(""),
    uniqueArrivalKey.scheduled,
    passengerList)
}

case class ManifestPassengerProfile(nationality: Nationality,
                                    documentType: Option[DocumentType],
                                    age: Option[PaxAge],
                                    inTransit: Option[Boolean])

object ManifestPassengerProfile {
  def apply(pij: PassengerInfoJson, portCode: PortCode): ManifestPassengerProfile = {
    val nationality = pij.NationalityCountryCode.getOrElse(Nationality(""))
    val documentType: Option[DocumentType] = if (nationality.code == CountryCodes.UK)
      Option(DocumentType.Passport)
    else
      pij.DocumentType
    val maybeInTransit = Option(pij.InTransitFlag.isInTransit|| pij.DisembarkationPortCode.exists(_ != portCode))
    ManifestPassengerProfile(nationality, documentType, pij.Age, maybeInTransit)
  }
}
