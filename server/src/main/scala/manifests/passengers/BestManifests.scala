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
  val nonUniquePassengers: Seq[ManifestPassengerProfile]
  val maybeEventType: Option[EventType]

  def uniquePassengers: Seq[ManifestPassengerProfile] = {
    if (nonUniquePassengers.exists(_.passengerIdentifier.exists(_.nonEmpty)))
      nonUniquePassengers
        .collect {
          case p@ManifestPassengerProfile(_, _, _, _, Some(id)) if id.nonEmpty => p
        }
        .map { passengerInfo =>
          passengerInfo.passengerIdentifier -> passengerInfo
        }
        .toMap
        .values
        .toList
    else
      nonUniquePassengers
  }

  def maybeKey: Option[ArrivalKey] = voyageNumber match {
    case vn: VoyageNumber =>
      Option(ArrivalKey(departurePortCode, vn, scheduled.millisSinceEpoch))
    case _ => None
  }
}

trait ManifestPaxLike {
  val source: SplitSource
  val arrivalPortCode: PortCode
  val departurePortCode: PortCode
  val voyageNumber: VoyageNumberLike
  val carrierCode: CarrierCode
  val scheduled: SDateLike
  val pax: Option[Int]
  val maybeEventType: Option[EventType]

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
                                 nonUniquePassengers: Seq[ManifestPassengerProfile],
                                 override val maybeEventType: Option[EventType]) extends ManifestLike

case class HistoricManifestPax(source: SplitSource,
                               arrivalPortCode: PortCode,
                               departurePortCode: PortCode,
                               voyageNumber: VoyageNumberLike,
                               carrierCode: CarrierCode,
                               scheduled: SDateLike,
                               pax: Option[Int],
                               override val maybeEventType: Option[EventType]) extends ManifestPaxLike

object HistoricManifestPax {

  def apply(manifest: VoyageManifest): HistoricManifestPax =
    fromManifest(manifest, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages)

  def historic(manifest: VoyageManifest): HistoricManifestPax =
    fromManifest(manifest, SplitSources.Historical)

  def fromManifest(manifest: VoyageManifest, source: SplitSource): HistoricManifestPax = {
    HistoricManifestPax(
      source = source,
      arrivalPortCode = manifest.ArrivalPortCode,
      departurePortCode = manifest.DeparturePortCode,
      voyageNumber = manifest.VoyageNumber,
      carrierCode = manifest.CarrierCode,
      scheduled = manifest.scheduleArrivalDateTime.getOrElse(SDate.now()),
      pax = Option(manifest.uniquePassengers.size),
      maybeEventType = Option(manifest.EventCode)
    )
  }

  def apply(source: SplitSource,
            uniqueArrivalKey: UniqueArrivalKey,
            pax: Int): HistoricManifestPax = HistoricManifestPax(
    source = source,
    arrivalPortCode = uniqueArrivalKey.arrivalPort,
    departurePortCode = uniqueArrivalKey.departurePort,
    voyageNumber = uniqueArrivalKey.voyageNumber,
    carrierCode = CarrierCode(""),
    scheduled = uniqueArrivalKey.scheduled,
    pax = Option(pax),
    maybeEventType = None)
}

object BestAvailableManifest {
  def apply(manifest: VoyageManifest): BestAvailableManifest =
    fromManifest(manifest, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages)

  def historic(manifest: VoyageManifest): BestAvailableManifest =
    fromManifest(manifest, SplitSources.Historical)

  def fromManifest(manifest: VoyageManifest, source: SplitSource): BestAvailableManifest = {
    BestAvailableManifest(
      source = source,
      arrivalPortCode = manifest.ArrivalPortCode,
      departurePortCode = manifest.DeparturePortCode,
      voyageNumber = manifest.VoyageNumber,
      carrierCode = manifest.CarrierCode,
      scheduled = manifest.scheduleArrivalDateTime.getOrElse(SDate.now()),
      nonUniquePassengers = manifest.uniquePassengers,
      maybeEventType = Option(manifest.EventCode)
    )
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
    nonUniquePassengers = passengerList,
    maybeEventType = None)
}

case class ManifestPassengerProfile(nationality: Nationality,
                                    documentType: Option[DocumentType],
                                    age: Option[PaxAge],
                                    inTransit: Boolean,
                                    passengerIdentifier: Option[String])

object ManifestPassengerProfile {
  def apply(pij: PassengerInfoJson, portCode: PortCode): ManifestPassengerProfile =
    ManifestPassengerProfile(
      nationality = pij.NationalityCountryCode.getOrElse(Nationality("")),
      documentType = pij.docTypeWithNationalityAssumption,
      age = pij.Age,
      inTransit = pij.isInTransit(portCode),
      passengerIdentifier = pij.PassengerIdentifier
    )
}
