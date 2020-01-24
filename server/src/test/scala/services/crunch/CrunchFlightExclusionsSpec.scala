package services.crunch

import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues._
import drt.shared.Terminals.{InvalidTerminal, T1}
import drt.shared._
import server.feeds.ArrivalsFeedSuccess
import services.SDate

import scala.collection.immutable.{List, Seq, SortedMap}
import scala.concurrent.duration._

class CrunchFlightExclusionsSpec extends CrunchTestLike {
  sequential
  isolated

  "Given two flights, one with an invalid terminal " +
    "When I ask for a crunch " +
    "I should only see crunch results for the flight with a valid terminal" >> {
    val scheduled00 = "2017-01-01T00:00Z"
    val scheduled01 = "2017-01-01T00:01Z"

    val scheduled = "2017-01-01T00:00Z"

    val flights = Flights(List(
      ArrivalGenerator.arrival(schDt = scheduled00, iata = "BA0001", terminal = T1, actPax = Option(15)),
      ArrivalGenerator.arrival(schDt = scheduled01, iata = "FR8819", terminal = InvalidTerminal, actPax = Option(10))
    ))

    val fiveMinutes = 600d / 60

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = defaultAirportConfig.copy(
        terminalProcessingTimes = Map(T1 -> Map(eeaMachineReadableToDesk -> fiveMinutes)),
        queuesByTerminal = SortedMap(T1 -> Seq(Queues.EeaDesk))
      ),
      minutesToCrunch = 120)

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

    val expected = Map(
      T1 -> Map(Queues.EeaDesk -> Seq(
        15.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))

    crunch.portStateTestProbe.fishForMessage(5 seconds) {
      case ps: PortState =>
        val resultSummary = paxLoadsFromPortState(ps, 30)
        resultSummary == expected
    }

    crunch.shutdown

    success
  }

  "Given four flights, one with status 'cancelled', one status 'canceled', and one with status 'deleted' " +
    "When I ask for a crunch " +
    "I should only see crunch results for the one flight that was not cancelled" >> {
    val scheduled00 = "2017-01-01T00:00Z"
    val scheduled01 = "2017-01-01T00:05Z"
    val scheduled02 = "2017-01-01T00:10Z"
    val scheduled03 = "2017-01-01T00:15Z"

    val scheduled = "2017-01-01T00:00Z"

    val flights = Flights(List(
      ArrivalGenerator.arrival(schDt = scheduled00, iata = "BA0001", terminal = T1, actPax = Option(15), status = ArrivalStatus("On time")),
      ArrivalGenerator.arrival(schDt = scheduled01, iata = "FR8819", terminal = T1, actPax = Option(10), status = ArrivalStatus("xx cancelled xx")),
      ArrivalGenerator.arrival(schDt = scheduled02, iata = "BA1000", terminal = T1, actPax = Option(10), status = ArrivalStatus("xx canceled xx")),
      ArrivalGenerator.arrival(schDt = scheduled03, iata = "ZX0888", terminal = T1, actPax = Option(10), status = ArrivalStatus("xx deleted xx"))
    ))

    val fiveMinutes = 600d / 60

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = defaultAirportConfig.copy(
        terminalProcessingTimes = Map(T1 -> Map(eeaMachineReadableToDesk -> fiveMinutes)),
        queuesByTerminal = SortedMap(T1 -> Seq(Queues.EeaDesk))
      ),
      minutesToCrunch = 120
    )

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

    val expected = Map(
      T1 -> Map(Queues.EeaDesk -> Seq(
        15.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))

    crunch.portStateTestProbe.fishForMessage(10 seconds) {
      case ps: PortState =>
        val resultSummary = paxLoadsFromPortState(ps, 30)
        resultSummary == expected
    }

    crunch.shutdown

    success
  }
}
