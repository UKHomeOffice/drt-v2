package actors.routing

import actors.PartitionedPortStateActor.{GetFlights, GetFlightsForTerminalDateRange}
import actors.{ArrivalGenerator, FlightLookups}
import akka.NotUsed
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import controllers.model.{RedListCount, RedListCounts}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.{T1, T2}
import drt.shared.{ArrivalsDiff, PortCode}
import services.SDate
import services.crunch.CrunchTestLike

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class FlightsRouterActorSpec extends CrunchTestLike {
  val updatesProbe: TestProbe = TestProbe("updates")
  "A flights router actor" should {
    val now = SDate("2021-06-24T12:10:00")
    val lookups = FlightLookups(system, () => now, Map(T1 -> Seq(), T2 -> Seq()), updatesProbe.ref, None)
    val flightsRouter = lookups.flightsActor
    val scheduled = "2021-06-24T10:25"
    val redListPax = 10
    val redListPax2 = 15
    "Add red list pax to an existing arrival" in {
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, schDt = scheduled)
      Await.ready(flightsRouter ? ArrivalsDiff(Seq(arrival), Seq()), 1.second)
      Await.ready(flightsRouter ? RedListCounts(Seq(RedListCount("BA0001", PortCode("LHR"), SDate(scheduled), redListPax))), 1.second)
      val eventualFlights = (flightsRouter ? GetFlightsForTerminalDateRange(now.getLocalLastMidnight.millisSinceEpoch, now.getLocalNextMidnight.millisSinceEpoch, T1)).flatMap {
        case source: Source[FlightsWithSplits, NotUsed] => source.runFold(FlightsWithSplits.empty)(_ ++ _)
      }
      val flights = Await.result(eventualFlights, 1.second)

      flights.flights.values.head.apiFlight === arrival.copy(RedListPax = Option(redListPax))
    }

    "Add red list pax counts to the appropriate arrivals" in {
      val scheduled2 = "2021-06-24T15:05"
      val arrivalT1 = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, schDt = scheduled)
      val arrivalT2 = ArrivalGenerator.arrival(iata = "AB1234", terminal = T2, schDt = scheduled2)
      Await.ready(flightsRouter ? ArrivalsDiff(Seq(arrivalT1, arrivalT2), Seq()), 1.second)
      val redListPax = 10
      Await.ready(flightsRouter ? RedListCounts(Seq(
        RedListCount("BA0001", PortCode("LHR"), SDate(scheduled), redListPax),
        RedListCount("EZT1234", PortCode("LHR"), SDate(scheduled2), redListPax2),
      )), 1.second)
      val eventualFlights = (flightsRouter ? GetFlights(now.getLocalLastMidnight.millisSinceEpoch, now.getLocalNextMidnight.millisSinceEpoch)).flatMap {
        case source: Source[FlightsWithSplits, NotUsed] => source.runFold(FlightsWithSplits.empty)(_ ++ _)
      }
      val arrivals = Await.result(eventualFlights, 1.second).flights.values.map(_.apiFlight).toList

      arrivals.contains(arrivalT1.copy(RedListPax = Option(redListPax))) && arrivals.contains(arrivalT2.copy(RedListPax = Option(redListPax2)))
    }
  }
}
