package services.crunch

import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared._
import server.feeds.ArrivalsFeedSuccess
import services.SDate
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues.eeaMachineReadableToDesk
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import uk.gov.homeoffice.drt.ports.Terminals.{InvalidTerminal, T1}
import uk.gov.homeoffice.drt.ports.{PaxTypeAndQueue, PaxTypes, Queues}

import scala.collection.immutable.{List, Seq, SortedMap}
import scala.concurrent.duration._

class CrunchQueueAndTerminalValidationSpec extends CrunchTestLike {
  sequential
  isolated

  "Queue validation " >> {
    "Given a flight with transfers " >> {
      "When I ask for a crunch " >> {
        "Then I should see only the non-transfer queue" >> {
          val scheduled00 = "2017-01-01T00:00Z"
          val scheduled = "2017-01-01T00:00Z"

          val flights = Flights(Seq(
            ArrivalGenerator.arrival(schDt = scheduled00, iata = "BA0001", terminal = T1, actPax = Option(15))
            ))

          val fiveMinutes = 600d / 60

          val crunch = runCrunchGraph(TestConfig(
            now = () => SDate(scheduled),
            airportConfig = defaultAirportConfig.copy(
              terminalPaxSplits = Map(T1 -> SplitRatios(
                SplitSources.TerminalAverage,
                SplitRatio(eeaMachineReadableToDesk, 1),
                SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.Transfer), 1)
                )),
              terminalProcessingTimes = Map(T1 -> Map(eeaMachineReadableToDesk -> fiveMinutes)),
              queuesByTerminal = SortedMap(T1 -> Seq(EeaDesk))
              )))

          offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

          val expected = Set(Queues.EeaDesk)

          crunch.portStateTestProbe.fishForMessage(1.seconds) {
            case ps: PortState =>
              val resultSummary = paxLoadsFromPortState(ps, 1).flatMap(_._2.keys).toSet
              println(s"resultSummary: $resultSummary")
              resultSummary == expected
          }

          success
        }
      }
    }
  }

  "Given two flights, one with an invalid terminal " >> {
    "When I ask for a crunch " >> {
      "I should only see crunch results for the flight with a valid terminal" >> {

        val scheduled = "2017-01-01T00:00Z"

        val flights = Flights(Seq(
          ArrivalGenerator.arrival(schDt = scheduled, iata = "BA0001", terminal = T1, actPax = Option(15)),
          ArrivalGenerator.arrival(schDt = scheduled, iata = "FR8819", terminal = InvalidTerminal, actPax = Option(10))
        ))

        val fiveMinutes = 600d / 60

        val crunch = runCrunchGraph(TestConfig(
          now = () => SDate(scheduled),
          airportConfig = defaultAirportConfig.copy(
            terminalProcessingTimes = Map(T1 -> Map(eeaMachineReadableToDesk -> fiveMinutes)),
            queuesByTerminal = SortedMap(T1 -> Seq(EeaDesk))
          )
        ))

        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

        val expected = Map(T1 -> Map(Queues.EeaDesk -> List(15.0)))

        crunch.portStateTestProbe.fishForMessage(1.seconds) {
          case ps: PortState =>
            val resultSummary = paxLoadsFromPortState(ps, 1, SDate(scheduled))
            resultSummary == expected
        }

        success
      }
    }
  }
}
