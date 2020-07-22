package services.arrivals

import actors.ArrivalGenerator
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import drt.shared.{ArrivalsDiff, UniqueArrival}
import org.specs2.mutable.Specification

import scala.collection.immutable.SortedMap

class EdiFlightAdjustmentsSpec extends Specification {

  val ediAdjusterWithNoHistoricMappings: EdiArrivalsTerminalAdjustments = EdiArrivalsTerminalAdjustments(Map())

  def toArrivalsDiff(updated: List[Arrival] = List(), toRemove: List[Arrival] = List()) = {
    ArrivalsDiff(SortedMap[UniqueArrival, Arrival]() ++ updated.map(a => a.unique -> a).toMap, toRemove.toSet)
  }

  "Given an arrivals diff with a flight that has no baggage carousel then it should use the default A1" >> {
    val arrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("T1"), schDt = "2020-07-17T14:00Z")
    val arrival2 = ArrivalGenerator.arrival(iata = "TST200", terminal = Terminal("T1"), schDt = "2020-07-17T15:00Z")

    val arrivals = List(arrival, arrival2)

    val arrivalsDiff = toArrivalsDiff(arrivals)

    val expected = toArrivalsDiff(List(
      arrival.copy(Terminal = Terminal("A1")),
      arrival2.copy(Terminal = Terminal("A1"))
    ))

    val result = ediAdjusterWithNoHistoricMappings(arrivalsDiff)

    result === expected

  }

  "Given an arrivals diff with a flight that has a baggage carousel that isn't 7 then it should default to A1" >> {
    val arrival = ArrivalGenerator.arrival(iata = "TST100",
      terminal = Terminal("T1"),
      schDt = "2020-07-17T14:00Z",
      baggageReclaimId = Option("6")
    )
    val arrival2 = ArrivalGenerator.arrival("TST200", "A1", "2020-07-17T15:00Z")

    val arrivalsDiff = toArrivalsDiff(List(arrival, arrival2))

    val expected = toArrivalsDiff(List(
      arrival.copy(Terminal = Terminal("A1")),
      arrival2.copy(Terminal = Terminal("A1"))
    ))

    val result = ediAdjusterWithNoHistoricMappings(arrivalsDiff)

    result === expected
  }

  "Given an arrivals diff with a flight that has baggage carousel 7 then it should be updated to A2 terminal" >> {
    val arrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("T1"), schDt = "2020-07-17T14:00Z", baggageReclaimId = Option("7"))
    val arrival2 = ArrivalGenerator.arrival(iata = "TST200", terminal = Terminal("T1"), schDt = "2020-07-17T15:00Z")

    val arrivalsDiff = toArrivalsDiff(List(arrival, arrival2))

    val expected = toArrivalsDiff(List(arrival.copy(Terminal = Terminal("A2")), arrival2.copy(Terminal = Terminal("A1"))))

    val result = ediAdjusterWithNoHistoricMappings(arrivalsDiff)

    result === expected
  }

  "Given an arrivals diff with removals containing a flight with no baggage carousel then it should default to A1" >> {
    val arrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("T1"), schDt = "2020-07-17T14:00Z")
    val arrival2 = ArrivalGenerator.arrival(iata = "TST200", terminal = Terminal("T1"), schDt = "2020-07-17T15:00Z")

    val arrivals = List(arrival, arrival2)

    val arrivalsDiff = toArrivalsDiff(toRemove = arrivals)

    val expected = toArrivalsDiff(toRemove = List(
      arrival.copy(Terminal = Terminal("A1")),
      arrival2.copy(Terminal = Terminal("A1"))
    ))

    val result = ediAdjusterWithNoHistoricMappings(arrivalsDiff)

    result === expected
  }

  "Given an arrivals diff with removals containing a flight that has a baggage carousel that isn't 7 then it should default to A1" >> {
    val arrival = ArrivalGenerator.arrival(iata = "TST100",
      terminal = Terminal("T1"),
      schDt = "2020-07-17T14:00Z",
      baggageReclaimId = Option("6")
    )
    val arrival2 = ArrivalGenerator.arrival(iata = "TST200", terminal = Terminal("T1"), schDt = "2020-07-17T15:00Z")

    val arrivalsDiff = toArrivalsDiff(toRemove = List(arrival, arrival2))

    val expected = toArrivalsDiff(toRemove = List(
      arrival.copy(Terminal = Terminal("A1")),
      arrival2.copy(Terminal = Terminal("A1"))
    ))

    val result = ediAdjusterWithNoHistoricMappings(arrivalsDiff)

    result === expected
  }

  "Given an arrivals diff with removals containing a flight that has baggage carousel 7 then it should be updated to A2 terminal" >> {
    val arrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("T1"), schDt = "2020-07-17T14:00Z", baggageReclaimId = Option("7"))
    val arrival2 = ArrivalGenerator.arrival(iata = "TST200", terminal = Terminal("T1"), schDt = "2020-07-17T15:00Z")

    val arrivalsDiff = toArrivalsDiff(toRemove = List(arrival, arrival2))

    val expected = toArrivalsDiff(toRemove = List(arrival.copy(Terminal = Terminal("A2")), arrival2.copy(Terminal = Terminal("A1"))))

    val result = ediAdjusterWithNoHistoricMappings(arrivalsDiff)

    result === expected
  }

  "Given an EdiArrivalsTerminalAdjustments with historic details for a flight" >> {

    "When an updated flight has a historic entry and no baggage carousel then it should use the historic terminal" >> {
      val ediAdjustMentsWithHistoricMappingForFlight = EdiArrivalsTerminalAdjustments(
        Map("TST0100" -> Map("July" -> Terminal("A2")))
      )

      val arrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("T1"), schDt = "2020-07-17T14:00Z")
      val arrivalsDiff = toArrivalsDiff(List(arrival))
      val expected = toArrivalsDiff(List(arrival.copy(Terminal = Terminal("A2"))))

      val result = ediAdjustMentsWithHistoricMappingForFlight(arrivalsDiff)

      result === expected
    }

    "When an updated flight has no historic entry and no baggage carousel then it should default to A1" >> {
      val ediAdjustMentsWithHistoricMappingForFlight = EdiArrivalsTerminalAdjustments(Map())

      val arrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("T1"), schDt = "2020-07-17T14:00Z")
      val arrivalsDiff = toArrivalsDiff(List(arrival))
      val expected = toArrivalsDiff(List(arrival.copy(Terminal = Terminal("A1"))))

      val result = ediAdjustMentsWithHistoricMappingForFlight(arrivalsDiff)

      result === expected
    }

    "When a removed flight has a historic entry and no baggage carousel then it should use the historic terminal" >> {
      val ediAdjustMentsWithHistoricMappingForFlight = EdiArrivalsTerminalAdjustments(
        Map("TST0100" -> Map("July" -> Terminal("A2")))
      )

      val arrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("T1"), schDt = "2020-07-17T14:00Z")
      val arrivalsDiff = toArrivalsDiff(toRemove = List(arrival))
      val expected = toArrivalsDiff(toRemove = List(arrival.copy(Terminal = Terminal("A2"))))

      val result = ediAdjustMentsWithHistoricMappingForFlight(arrivalsDiff)

      result === expected
    }

    "When a removed flight has no historic entry and no baggage carousel then it should default to A1" >> {
      val ediAdjustMentsWithNoHistoricMappingForFlight = EdiArrivalsTerminalAdjustments(Map())

      val arrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("T1"), schDt = "2020-07-17T14:00Z")
      val arrivalsDiff = toArrivalsDiff(toRemove = List(arrival))
      val expected = toArrivalsDiff(toRemove = List(arrival.copy(Terminal = Terminal("A1"))))

      val result = ediAdjustMentsWithNoHistoricMappingForFlight(arrivalsDiff)

      result === expected
    }

    "When an updated flight has a historic entry of A2 and a baggage id of 6 it should adjust to A1" >> {
      val ediAdjustMentsWithHistoricMappingForFlight = EdiArrivalsTerminalAdjustments(
        Map("TST0100" -> Map("July" -> Terminal("A2")))
      )

      val arrival = ArrivalGenerator.arrival(
        iata = "TST100",
        terminal = Terminal("T1"),
        schDt = "2020-07-17T14:00Z",
        baggageReclaimId = Option("6")
      )
      val arrivalsDiff = toArrivalsDiff(List(arrival))
      val expected = toArrivalsDiff(List(arrival.copy(Terminal = Terminal("A1"))))

      val result = ediAdjustMentsWithHistoricMappingForFlight(arrivalsDiff)

      result === expected
    }

    "When an updated flight has a historic entry of A1 and a baggage id of 7 it should adjust to A2" >> {
      val ediAdjustMentsWithHistoricMappingForFlight = EdiArrivalsTerminalAdjustments(
        Map("TST0100" -> Map("July" -> Terminal("A1")))
      )

      val arrival = ArrivalGenerator.arrival(
        iata = "TST100",
        terminal = Terminal("T1"),
        schDt = "2020-07-17T14:00Z",
        baggageReclaimId = Option("7")
      )
      val arrivalsDiff = toArrivalsDiff(List(arrival))
      val expected = toArrivalsDiff(List(arrival.copy(Terminal = Terminal("A2"))))

      val result = ediAdjustMentsWithHistoricMappingForFlight(arrivalsDiff)

      result === expected
    }
  }
  "When a removed flight has a historic entry of A2 and a baggage id of 6 it should adjust to A1" >> {
    val ediAdjustMentsWithHistoricMappingForFlight = EdiArrivalsTerminalAdjustments(
      Map("TST0100" -> Map("July" -> Terminal("A2")))
    )

    val arrival = ArrivalGenerator.arrival(
      iata = "TST100",
      terminal = Terminal("T1"),
      schDt = "2020-07-17T14:00Z",
      baggageReclaimId = Option("6")
    )
    val arrivalsDiff = toArrivalsDiff(toRemove = List(arrival))
    val expected = toArrivalsDiff(toRemove = List(arrival.copy(Terminal = Terminal("A1"))))

    val result = ediAdjustMentsWithHistoricMappingForFlight(arrivalsDiff)

    result === expected
  }

  "When a removed flight has a historic entry of A1 and a baggage id of 7 it should adjust to A2" >> {
    val ediAdjustMentsWithHistoricMappingForFlight = EdiArrivalsTerminalAdjustments(
      Map("TST0100" -> Map("July" -> Terminal("A1")))
    )

    val arrival = ArrivalGenerator.arrival(
      iata = "TST100",
      terminal = Terminal("T1"),
      schDt = "2020-07-17T14:00Z",
      baggageReclaimId = Option("7")
    )
    val arrivalsDiff = toArrivalsDiff(toRemove = List(arrival))
    val expected = toArrivalsDiff(toRemove = List(arrival.copy(Terminal = Terminal("A2"))))

    val result = ediAdjustMentsWithHistoricMappingForFlight(arrivalsDiff)

    result === expected
  }


  "Given a CSV string with 1 flight, alternating terminals " >> {
    "When I apply the adjustments to an arrivals diff" >> {
      "Then I should see the historic reflected in the diff" >> {
        val csvStringWithAlternatingTerminals =
          """|code,Total Arrivals,A1_Winter,A2_Winter,A1_Summer,A2_Summer,January,February,March,April,May,June,July,August,September,October,November,December
             |TST100,1,100,0,0,0,A2,A1,A2,A1,A2,A1,A2,A1,A2,A1,A2,A1
             |TST200,1,100,0,0,0,A2,A1,A2,A1,A2,A1,A2,A1,A2,A1,A2,A1"""
            .stripMargin

        val arrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("T1"), schDt = "2020-01-17T14:00Z")
        val arrival2 = ArrivalGenerator.arrival(iata = "TST200", terminal = Terminal("T1"), schDt = "2020-01-17T14:00Z")
        val arrivalsDiff = toArrivalsDiff(updated = List(arrival), toRemove = List(arrival2))

        val expected = toArrivalsDiff(
          updated = List(arrival.copy(Terminal = Terminal("A2"))),
          toRemove = List(arrival2.copy(Terminal = Terminal("A2")))
        )

        val ediAdjustments = EdiArrivalsTerminalAdjustments(
          EdiArrivalTerminalCsvMapper.csvStringToMap(csvStringWithAlternatingTerminals)
        )

        val result = ediAdjustments(arrivalsDiff)

        result === expected
      }
    }
  }

}
