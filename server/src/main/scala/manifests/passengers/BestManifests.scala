package manifests.passengers

import drt.shared._
import manifests.UniqueArrivalKey
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest}
import services.SDate
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.{CarrierCode, EventType, VoyageNumber, VoyageNumberLike}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.{SplitSource, SplitSources}
import uk.gov.homeoffice.drt.ports.{PaxAge, PortCode}
import uk.gov.homeoffice.drt.time.SDateLike

trait ManifestLike {
  val source: SplitSource
  val arrivalPortCode: PortCode
  val departurePortCode: PortCode
  val voyageNumber: VoyageNumberLike
  val carrierCode: CarrierCode
  val scheduled: SDateLike
  val passengers: List[ManifestPassengerProfile]
  val maybeEventType: Option[EventType]

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
                                 passengers: List[ManifestPassengerProfile],
                                 override val maybeEventType: Option[EventType]) extends ManifestLike

object BestAvailableManifest {
  def apply(manifest: VoyageManifest): BestAvailableManifest =
    fromManifest(manifest, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages)

  def historic(manifest: VoyageManifest): BestAvailableManifest =
    fromManifest(manifest, SplitSources.Historical)

  def fromManifest(manifest: VoyageManifest, source: SplitSource): BestAvailableManifest = {
    val uniquePax: List[PassengerInfoJson] = removeDuplicatePax(manifest)

    BestAvailableManifest(
      source = source,
      arrivalPortCode = manifest.ArrivalPortCode,
      departurePortCode = manifest.DeparturePortCode,
      voyageNumber = manifest.VoyageNumber,
      carrierCode = manifest.CarrierCode,
      scheduled = manifest.scheduleArrivalDateTime.getOrElse(SDate.now()),
      passengers = uniquePax.map(p => ManifestPassengerProfile(p, manifest.ArrivalPortCode)),
      maybeEventType = Option(manifest.EventCode)
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
    source = source,
    arrivalPortCode = uniqueArrivalKey.arrivalPort,
    departurePortCode = uniqueArrivalKey.departurePort,
    voyageNumber = uniqueArrivalKey.voyageNumber,
    carrierCode = CarrierCode(""),
    scheduled = uniqueArrivalKey.scheduled,
    passengers = passengerList,
    maybeEventType = None)
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
