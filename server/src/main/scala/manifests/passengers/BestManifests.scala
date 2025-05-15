package manifests.passengers

import manifests.UniqueArrivalKey
import uk.gov.homeoffice.drt.arrivals.{CarrierCode, EventType, VoyageNumberLike}
import uk.gov.homeoffice.drt.models.{ManifestLike, ManifestPassengerProfile, VoyageManifest}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.{SplitSource, SplitSources}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}


case class BestAvailableManifest(source: SplitSource,
                                 arrivalPortCode: PortCode,
                                 departurePortCode: PortCode,
                                 voyageNumber: VoyageNumberLike,
                                 carrierCode: CarrierCode,
                                 scheduled: SDateLike,
                                 nonUniquePassengers: Seq[ManifestPassengerProfile],
                                 override val maybeEventType: Option[EventType]) extends ManifestLike

object BestAvailableManifest {
  def apply(manifest: VoyageManifest): BestAvailableManifest =
    fromManifest(manifest, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages)

  def historic(manifest: VoyageManifest): BestAvailableManifest =
    fromManifest(manifest, SplitSources.Historical)

  private def fromManifest(manifest: VoyageManifest, source: SplitSource): BestAvailableManifest = {
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
