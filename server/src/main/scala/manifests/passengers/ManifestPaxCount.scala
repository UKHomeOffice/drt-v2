package manifests.passengers

import manifests.UniqueArrivalKey
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import uk.gov.homeoffice.drt.arrivals.{CarrierCode, EventType, VoyageNumberLike}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSource
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

case class ManifestPaxCount(source: SplitSource,
                            arrivalPortCode: PortCode,
                            departurePortCode: PortCode,
                            voyageNumber: VoyageNumberLike,
                            carrierCode: CarrierCode,
                            scheduled: SDateLike,
                            pax: Option[Int],
                            maybeEventType: Option[EventType])


object ManifestPaxCount {

  def apply(manifest: VoyageManifest,
            source: SplitSource): ManifestPaxCount = {
    ManifestPaxCount(
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
