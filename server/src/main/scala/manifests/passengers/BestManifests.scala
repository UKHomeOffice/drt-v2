package manifests.passengers

import drt.shared.SplitRatiosNs.{SplitSource, SplitSources}
import drt.shared.{SDateLike, _}
import manifests.UniqueArrivalKey
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest}
import services.SDate

import scala.collection.immutable

trait ManifestLike {
  val source: SplitSource
  val arrivalPortCode: PortCode
  val departurePortCode: PortCode
  val voyageNumber: VoyageNumberLike
  val carrierCode: CarrierCode
  val scheduled: SDateLike
  val passengers: List[ManifestPassengerProfile]

  def uniquePassengers: Seq[ManifestPassengerProfile] = {
    if (passengers.exists(_.passengerIdentifier.exists(_ != "")))
      passengers.collect {
        case p@ManifestPassengerProfile(_, _, _, _, Some(id)) if id != "" => p
      }
        .map { passengerInfo =>
          passengerInfo.passengerIdentifier -> passengerInfo
        }
        .toMap
        .values
        .toList
    else
      passengers
  }

  def excludeTransitPax(manifest: VoyageManifest): VoyageManifest = manifest.copy(
    PassengerList = manifest
      .PassengerList
      .filterNot(_.isInTransit(manifest.ArrivalPortCode))
  )

  def maybeKey: Option[ArrivalKey] = voyageNumber match {
    case vn: VoyageNumber =>
      Option(ArrivalKey(departurePortCode, vn, scheduled.millisSinceEpoch))
    case _ => None
  }
}

case class BestAvailableManifest(source: SplitSource,
                                 arrivalPortCode: PortCode,
                                 departurePortCode: PortCode,
                                 voyageNumber: VoyageNumberLike,
                                 carrierCode: CarrierCode,
                                 scheduled: SDateLike,
                                 passengers: List[ManifestPassengerProfile]) extends ManifestLike

object BestAvailableManifest {
  def apply(manifest: VoyageManifest): BestAvailableManifest = {

    val uniquePax: List[PassengerInfoJson] = removeDuplicatePax(manifest)

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

  def historic(manifest: VoyageManifest): BestAvailableManifest = {

    val uniquePax: List[PassengerInfoJson] = removeDuplicatePax(manifest)

    BestAvailableManifest(
      SplitSources.Historical,
      manifest.ArrivalPortCode,
      manifest.DeparturePortCode,
      manifest.VoyageNumber,
      manifest.CarrierCode,
      manifest.scheduleArrivalDateTime.getOrElse(SDate.now()),
      uniquePax.map(p => ManifestPassengerProfile(p, manifest.ArrivalPortCode))
    )
  }

  def removeDuplicatePax(manifest: VoyageManifest): List[PassengerInfoJson] = {
    if (manifest.PassengerList.exists(_.PassengerIdentifier.exists(_ != "")))
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
  }

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
                                    inTransit: Option[Boolean],
                                    passengerIdentifier: Option[String])

object ManifestPassengerProfile {
  def apply(pij: PassengerInfoJson, portCode: PortCode): ManifestPassengerProfile =
    ManifestPassengerProfile(
      nationality = pij.NationalityCountryCode.getOrElse(Nationality("")),
      documentType = pij.docTypeWithNationalityAssumption,
      age = pij.Age,
      inTransit = Option(pij.isInTransit(portCode)),
      passengerIdentifier = pij.PassengerIdentifier
    )
}
