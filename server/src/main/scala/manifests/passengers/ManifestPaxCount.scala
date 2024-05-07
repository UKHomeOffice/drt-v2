package manifests.passengers

import manifests.UniqueArrivalKey
import uk.gov.homeoffice.drt.arrivals.{CarrierCode, EventType, VoyageNumberLike}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSource
import uk.gov.homeoffice.drt.time.SDateLike

case class ManifestPaxCount(source: SplitSource,
                            arrivalPortCode: PortCode,
                            departurePortCode: PortCode,
                            voyageNumber: VoyageNumberLike,
                            carrierCode: CarrierCode,
                            scheduled: SDateLike,
                            pax: Option[Int],
                            maybeEventType: Option[EventType])


object ManifestPaxCount {

  def apply(manifest: ManifestLike,
            source: SplitSource): ManifestPaxCount = {
    ManifestPaxCount(
      source = source,
      arrivalPortCode = manifest.arrivalPortCode,
      departurePortCode = manifest.departurePortCode,
      voyageNumber = manifest.voyageNumber,
      carrierCode = manifest.carrierCode,
      scheduled = manifest.scheduled,
      pax = Option(manifest.uniquePassengers.size),
      maybeEventType = manifest.maybeEventType,
    )
  }

  def apply(source: SplitSource,
            uniqueArrivalKey: UniqueArrivalKey,
            pax: Int): ManifestPaxCount = ManifestPaxCount(
    source = source,
    arrivalPortCode = uniqueArrivalKey.arrivalPort,
    departurePortCode = uniqueArrivalKey.departurePort,
    voyageNumber = uniqueArrivalKey.voyageNumber,
    carrierCode = CarrierCode(""),
    scheduled = uniqueArrivalKey.scheduled,
    pax = Option(pax),
    maybeEventType = None)
}
