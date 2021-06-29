package drt.shared.api

import drt.shared.Terminals.Terminal
import drt.shared.{ArrivalStatus, CarrierCode, FeedSource, PortCode, SDateLike, VoyageNumber}

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
