package services.crunch

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues._
import drt.shared._
import services.SDate
import services.graphstages.Crunch._

import scala.collection.immutable.{List, Seq}
import scala.concurrent.duration._


class CrunchCodeSharesSpec extends CrunchTestLike {
  sequential
  isolated

  val fiveMinutes = 600d / 60
  val procTimes = Map(
    "T1" -> Map(eeaMachineReadableToDesk -> fiveMinutes),
    "T2" -> Map(eeaMachineReadableToDesk -> fiveMinutes))

  "Code shares " >> {
    "Given 2 flights which are codeshares with each other " +
    "When I ask for a crunch " +
    "Then I should see workload representing only the flight with the highest passenger numbers" >> {
      val scheduled = "2017-01-01T00:00Z"

      val flights = Flights(List(
        ArrivalGenerator.apiFlight(flightId = 1, actPax = 10, schDt = scheduled, iata = "BA0001"),
        ArrivalGenerator.apiFlight(flightId = 2, actPax = 10, schDt = scheduled, iata = "FR8819")
      ))

      val crunch = runCrunchGraph(
        now = () => SDate(scheduled),
        airportConfig = airportConfig.copy(defaultProcessingTimes = procTimes),
        crunchStartDateProvider = (_) => getLocalLastMidnight(SDate(scheduled)),
        crunchEndDateProvider = (_) => getLocalLastMidnight(SDate(scheduled)).addMinutes(30)
      )

      offerAndWait(crunch.liveArrivalsInput, flights)

      val expected = Map("T1" -> Map(Queues.EeaDesk -> Seq(10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))

      crunch.liveTestProbe.fishForMessage(5 seconds) {
        case ps: PortState =>
          val resultSummary = paxLoadsFromPortState(ps, 15)
          resultSummary == expected
      }

      true
    }

    "Given flights some of which are code shares with each other " +
      "When I ask for a crunch " +
      "Then I should see workload correctly split to the appropriate terminals, and having accounted for code shares" >> {
      val scheduled00 = "2017-01-01T00:00Z"
      val scheduled15 = "2017-01-01T00:15Z"
      val scheduled = "2017-01-01T00:00Z"

      val flights = Flights(List(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled00, iata = "BA0001", terminal = "T1", actPax = 15),
        ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduled00, iata = "FR8819", terminal = "T1", actPax = 10),
        ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduled15, iata = "EZ1010", terminal = "T2", actPax = 12)
      ))

      val crunch = runCrunchGraph(
        now = () => SDate(scheduled),
        airportConfig = airportConfig.copy(defaultProcessingTimes = procTimes),
        crunchStartDateProvider = (_) => getLocalLastMidnight(SDate(scheduled)),
        crunchEndDateProvider = (_) => getLocalLastMidnight(SDate(scheduled)).addMinutes(30)
      )

      offerAndWait(crunch.liveArrivalsInput, flights)

      val expected = Map(
        "T1" -> Map(Queues.EeaDesk -> Seq(
          15.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
          0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
        "T2" -> Map(Queues.EeaDesk -> Seq(
          0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
          12.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))

      crunch.liveTestProbe.fishForMessage(10 seconds) {
        case ps: PortState =>
          val resultSummary = paxLoadsFromPortState(ps, 30)
          println(s"result: $resultSummary")
          resultSummary == expected
      }

      true
    }
  }

}
