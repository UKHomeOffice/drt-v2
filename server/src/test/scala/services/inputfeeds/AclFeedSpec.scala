package services.inputfeeds

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.Crunch.CrunchState
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues._
import drt.shared._
import services.SDate
import services.crunch.CrunchTestLike
import services.graphstages.Crunch._

import scala.collection.immutable.List
import scala.concurrent.duration._


class AclFeedSpec extends CrunchTestLike {
  "ACL Flights " >> {
    "Given an ACL feed with one flight and no live flights" +
      "When I ask for a crunch " +
      "Then I should see that flight in the CrunchState" >> {
      val scheduled = "2017-01-01T00:00Z"
      val aclFlight = List(Flights(List(
        ArrivalGenerator.apiFlight(flightId = 1, actPax = 10, schDt = scheduled, iata = "BA0001")
      )))

      val fiveMinutes = 600d / 60
      val procTimes: Map[PaxTypeAndQueue, Double] = Map(eeaMachineReadableToDesk -> fiveMinutes)

      val testProbe = TestProbe()
      val runnableGraphDispatcher =
        runCrunchGraph[NotUsed](procTimes = procTimes,
          testProbe = testProbe,
          crunchStartDateProvider = () => getLocalLastMidnight(SDate(scheduled)).millisSinceEpoch
        ) _

      runnableGraphDispatcher(Source(aclFlight), Source(List()), Source(List()))
      val result = testProbe.expectMsgAnyClassOf(10 seconds, classOf[CrunchState])

      val flightsResult = result.flights.map(_.apiFlight)

      val expected = Set(ArrivalGenerator.apiFlight(flightId = 1, actPax = 10, schDt = scheduled, iata = "BA0001"))

      flightsResult === expected
    }

    "Given an ACL feed with one flight and the same flight in the live feed" +
      "When I ask for a crunch " +
      "Then I should see the one flight in the CrunchState with the ACL flightcode and live chox" >> {
      val scheduled = "2017-01-01T00:00Z"
      val aclFlight = List(Flights(List(
        ArrivalGenerator.apiFlight(flightId = 1, actPax = 10, schDt = scheduled, iata = "BA0001")
      )))
      val liveFlight = List(Flights(List(
        ArrivalGenerator.apiFlight(flightId = 1, actPax = 10, schDt = scheduled, iata = "BAW001", actChoxDt = "2017-01-01T00:30Z")
      )))

      val fiveMinutes = 600d / 60
      val procTimes: Map[PaxTypeAndQueue, Double] = Map(eeaMachineReadableToDesk -> fiveMinutes)

      val testProbe = TestProbe()
      val runnableGraphDispatcher =
        runCrunchGraph[NotUsed](procTimes = procTimes,
          testProbe = testProbe,
          crunchStartDateProvider = () => getLocalLastMidnight(SDate(scheduled)).millisSinceEpoch
        ) _

      runnableGraphDispatcher(Source(aclFlight), Source(liveFlight), Source(List()))
      testProbe.expectMsgAnyClassOf(10 seconds, classOf[CrunchState])

      val result = testProbe.expectMsgAnyClassOf(10 seconds, classOf[CrunchState])

      val flightsResult = result.flights.map(_.apiFlight)

      val expected = Set(ArrivalGenerator.apiFlight(flightId = 1, actPax = 10, schDt = scheduled, iata = "BA0001", actChoxDt = "2017-01-01T00:30Z"))

      flightsResult === expected
    }

    "Given an ACL feed with no flights and one flight in the live feed" +
      "When I ask for a crunch " +
      "Then I should see the flight in the CrunchState" >> {
      val scheduled = "2017-01-01T00:00Z"

      val liveFlight = List(Flights(List(
        ArrivalGenerator.apiFlight(flightId = 1, actPax = 10, schDt = scheduled, iata = "BAW001", actChoxDt = "2017-01-01T00:30Z")
      )))

      val fiveMinutes = 600d / 60
      val procTimes: Map[PaxTypeAndQueue, Double] = Map(eeaMachineReadableToDesk -> fiveMinutes)

      val testProbe = TestProbe()
      val runnableGraphDispatcher =
        runCrunchGraph[NotUsed](procTimes = procTimes,
          testProbe = testProbe,
          crunchStartDateProvider = () => getLocalLastMidnight(SDate(scheduled)).millisSinceEpoch
        ) _

      runnableGraphDispatcher(Source(List()), Source(liveFlight), Source(List()))

      val result = testProbe.expectMsgAnyClassOf(10 seconds, classOf[CrunchState])

      val flightsResult = result.flights.map(_.apiFlight)

      val expected = Set(ArrivalGenerator.apiFlight(flightId = 1, actPax = 10, schDt = scheduled, iata = "BAW001", actChoxDt = "2017-01-01T00:30Z"))

      flightsResult === expected
    }
  }
}
