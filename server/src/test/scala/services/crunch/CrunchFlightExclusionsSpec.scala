package services.crunch

import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues._
import drt.shared._
import server.feeds.ArrivalsFeedSuccess
import services.SDate

import scala.collection.immutable.{List, Seq}
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
      ArrivalGenerator.arrival(schDt = scheduled00, iata = "BA0001", terminal = "T1", actPax = Option(15)),
      ArrivalGenerator.arrival(schDt = scheduled01, iata = "FR8819", terminal = "XXX", actPax = Option(10))
    ))

    val fiveMinutes = 600d / 60

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        defaultProcessingTimes = Map("T1" -> Map(eeaMachineReadableToDesk -> fiveMinutes)),
        queues = Map("T1" -> Seq(Queues.EeaDesk)),
        terminalNames = Seq("T1")
      ),
      minutesToCrunch = 120)

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

    val expected = Map(
      "T1" -> Map(Queues.EeaDesk -> Seq(
        15.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))

    crunch.portStateTestProbe.fishForMessage(5 seconds) {
      case ps: PortState =>
        val resultSummary = paxLoadsFromPortState(ps, 30)
        resultSummary == expected
    }

    crunch.liveArrivalsInput.complete()

    success
  }

  "Given two flights, one with a cancelled " +
    "When I ask for a crunch " +
    "I should only see crunch results for the flight with a valid terminal" >> {
    val scheduled00 = "2017-01-01T00:00Z"
    val scheduled01 = "2017-01-01T00:01Z"

    val scheduled = "2017-01-01T00:00Z"

    val flights = Flights(List(
      ArrivalGenerator.arrival(schDt = scheduled00, iata = "BA0001", terminal = "T1", actPax = Option(15)),
      ArrivalGenerator.arrival(schDt = scheduled01, iata = "FR8819", terminal = "T1", actPax = Option(10), status = "Cancelled")
    ))

    val fiveMinutes = 600d / 60

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        defaultProcessingTimes = Map("T1" -> Map(eeaMachineReadableToDesk -> fiveMinutes)),
        queues = Map("T1" -> Seq(Queues.EeaDesk)),
        terminalNames = Seq("T1")),
      minutesToCrunch = 120
    )

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

    val expected = Map(
      "T1" -> Map(Queues.EeaDesk -> Seq(
        15.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))

    crunch.portStateTestProbe.fishForMessage(10 seconds) {
      case ps: PortState =>
        val resultSummary = paxLoadsFromPortState(ps, 30)
        resultSummary == expected
    }

    crunch.liveArrivalsInput.complete()

    success
  }
}
