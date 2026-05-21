package services.arrivals

import controllers.ArrivalGenerator
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.LiveFeedSource
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2}

class BhxFlightAdjustmentsSpec extends Specification {
  "Given a BHX arrival in T1 with a gate between 1 and 25, it should be assigned to T2" >> {
    val arrival = ArrivalGenerator.live(
      iata = "LS1136",
      terminal = T1,
      schDt = "2026-05-11T12:00Z",
      gate = Option("13"),
    )

    val result = BhxArrivalsTerminalAdjustments.adjust(arrival.toArrival(LiveFeedSource))

    result should ===(arrival.toArrival(LiveFeedSource).copy(Terminal = T2))
  }

  "Given a BHX arrival in T1 with an alphanumeric T2 gate, it should still be assigned to T2" >> {
    val arrival = ArrivalGenerator.live(
      iata = "LS1136",
      terminal = T1,
      schDt = "2026-05-11T12:00Z",
      gate = Option("8C"),
    )

    val result = BhxArrivalsTerminalAdjustments.adjust(arrival.toArrival(LiveFeedSource))

    result should ===(arrival.toArrival(LiveFeedSource).copy(Terminal = T2))
  }

  "Given a BHX arrival with a gate outside the T2 range, it should keep its terminal" >> {
    val arrival = ArrivalGenerator.live(
      iata = "LS1136",
      terminal = T1,
      schDt = "2026-05-11T12:00Z",
      gate = Option("44"),
    )

    val result = BhxArrivalsTerminalAdjustments.adjust(arrival.toArrival(LiveFeedSource))

    result should ===(arrival.toArrival(LiveFeedSource))
  }

  "Given a BHX arrival without a gate, it should keep its terminal" >> {
    val arrival = ArrivalGenerator.live(
      iata = "LS1136",
      terminal = T1,
      schDt = "2026-05-11T12:00Z",
    )

    val result = BhxArrivalsTerminalAdjustments.adjust(arrival.toArrival(LiveFeedSource))

    result should ===(arrival.toArrival(LiveFeedSource))
  }
}

