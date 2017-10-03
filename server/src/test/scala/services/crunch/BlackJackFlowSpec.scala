package services.crunch

import akka.actor.ActorRef
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.{ActualDeskStats, DeskStat}
import drt.shared.Crunch.{CrunchMinute, CrunchState}
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues._
import drt.shared.Queues._
import org.specs2.mutable.Specification
import passengersplits.parsing.VoyageManifestParser.PassengerInfoJson
import services.SDate

import scala.collection.immutable.Seq


class BlackJackFlowSpec extends CrunchTestLike {
  isolated
  sequential

  "Given a CrunchGraph when the blackjack CSV is updated " +
    "Then the updated blackjack numbers sould appear in the CrunchState" >> {

    val scheduled = "2017-01-01T00:00Z"

    val flight = ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 21)
    val flights = Flights(List(flight))

    val flightsSource = Source.actorRef(1, OverflowStrategy.dropBuffer)
    val manifestsSource = Source.actorRef(1, OverflowStrategy.dropBuffer)
    val testProbe = TestProbe()
    val runnableGraphDispatcher =
      runCrunchGraph[ActorRef, ActorRef](
        procTimes = Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60
        ),
        queues = Map("T1" -> Seq(EeaDesk, EGate)),
        testProbe = testProbe,
        crunchStartDateProvider = () => SDate(scheduled).millisSinceEpoch
      ) _

    val (fs, ms, _, ds) = runnableGraphDispatcher(flightsSource, manifestsSource)

    fs ! flights
    testProbe.expectMsgAnyClassOf(classOf[CrunchState])

    ds ! ActualDeskStats(Map(
    "T1" -> Map(
          EeaDesk -> Map(
            SDate(scheduled).millisSinceEpoch -> DeskStat(Option(1), Option(5)),
            SDate(scheduled).addMinutes(15).millisSinceEpoch -> DeskStat(Option(2), Option(10))
      ))))

    val crunchMinutes = testProbe.expectMsgAnyClassOf(classOf[CrunchState]) match {
      case CrunchState(_, _, _, c) => c
    }
    val actDesks = crunchMinutes.toList.sortBy(_.minute).map(cm => {
      (cm.actDesks, cm.actWait)
    })


    val expected = List.fill(15)((Option(1), Option(5))) ++ List.fill(15)((Option(2), Option(10)))

    actDesks === expected
  }

  def passengerInfoJson(nationality: String, documentType: String, issuingCountry: String): PassengerInfoJson = {
    PassengerInfoJson(Some(documentType), issuingCountry, "", Some("22"), Some("LHR"), "N", Some("GBR"), Option(nationality))
  }
}


