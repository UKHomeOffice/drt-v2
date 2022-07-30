package drt.shared.api

import uk.gov.homeoffice.drt.arrivals.{ArrivalStatus, CarrierCode, FlightCodeSuffix, VoyageNumber}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode}
import uk.gov.homeoffice.drt.time.SDateLike

case class ScheduledArrival(CarrierCode: CarrierCode,
                            VoyageNumber: VoyageNumber,
                            FlightCodeSuffix: Option[FlightCodeSuffix],
                            Status: ArrivalStatus,
                            MaxPax: Option[Int],
                            EstPax: Option[Int],
                            TranPax: Option[Int],
                            Terminal: Terminal,
                            Origin: PortCode,
                            Scheduled: SDateLike,
                            FeedSources: Set[FeedSource])
