package drt.shared.api

import drt.shared.{ArrivalStatus, CarrierCode, SDateLike, VoyageNumber}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode}

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
