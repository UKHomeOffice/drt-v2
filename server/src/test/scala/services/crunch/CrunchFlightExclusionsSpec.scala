package services.crunch

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues._
import drt.shared._
import services.SDate
import services.graphstages.Crunch._

import scala.collection.immutable.{List, Seq}

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
      ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled00, iata = "BA0001", terminal = "T1", actPax = 15),
      ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduled01, iata = "FR8819", terminal = "XXX", actPax = 10)
    ))

    val fiveMinutes = 600d / 60

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(defaultProcessingTimes = Map("T1" -> Map(eeaMachineReadableToDesk -> fiveMinutes))),
      minutesToCrunch = 120,
      crunchStartDateProvider = (_) => getLocalLastMidnight(SDate(scheduled)),
      crunchEndDateProvider = (_) => getLocalLastMidnight(SDate(scheduled)).addMinutes(120)
    )

    crunch.liveArrivalsInput.offer(flights)

    val result = crunch.liveTestProbe.expectMsgAnyClassOf(classOf[PortState])
    val resultSummary = paxLoadsFromPortState(result, 30)

    val expected = Map(
      "T1" -> Map(Queues.EeaDesk -> Seq(
        15.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))

    resultSummary === expected
  }

  "Given two flights, one with a cancelled " +
    "When I ask for a crunch " +
    "I should only see crunch results for the flight with a valid terminal" >> {
    val scheduled00 = "2017-01-01T00:00Z"
    val scheduled01 = "2017-01-01T00:01Z"

    val scheduled = "2017-01-01T00:00Z"

    val flights = Flights(List(
      ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled00, iata = "BA0001", terminal = "T1", actPax = 15),
      ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduled01, iata = "FR8819", terminal = "T1", actPax = 10, status = "Cancelled")
    ))

    val fiveMinutes = 600d / 60

    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(defaultProcessingTimes = Map("T1" -> Map(eeaMachineReadableToDesk -> fiveMinutes))),
      minutesToCrunch = 120,
      crunchStartDateProvider = (_) => getLocalLastMidnight(SDate(scheduled)),
      crunchEndDateProvider = (_) => getLocalLastMidnight(SDate(scheduled)).addMinutes(120)
    )

    crunch.liveArrivalsInput.offer(flights)

    val result = crunch.liveTestProbe.expectMsgAnyClassOf(classOf[PortState])
    val resultSummary = paxLoadsFromPortState(result, 30)

    val expected = Map(
      "T1" -> Map(Queues.EeaDesk -> Seq(
        15.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))

    resultSummary === expected
  }
}
