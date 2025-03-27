package services.crunch

import controllers.ArrivalGenerator
import drt.server.feeds.ArrivalsFeedSuccess
import drt.shared._
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues.eeaMachineReadableToDesk
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import uk.gov.homeoffice.drt.ports.Terminals.{InvalidTerminal, T1}
import uk.gov.homeoffice.drt.ports.{PaxTypeAndQueue, PaxTypes, Queues}
import uk.gov.homeoffice.drt.time.SDate

import scala.collection.immutable.{List, Seq, SortedMap}
import scala.concurrent.duration._

class CrunchQueueAndTerminalValidationSpec extends CrunchTestLike {
  sequential
  isolated

  private val oneMinute = 60d / 60

  "Queue validation " >> {
    "Given a flight with transfers " >> {
      "When I ask for a crunch " >> {
        "Then I should see only the non-transfer queue" >> {
          val scheduled00 = "2017-01-01T00:00Z"
          val scheduled = "2017-01-01T00:00Z"

          val flights = Seq(
            ArrivalGenerator.live(schDt = scheduled00, iata = "BA0001", terminal = T1, totalPax = Option(15))
          )

          val crunch = runCrunchGraph(TestConfig(
            now = () => SDate(scheduled),
            airportConfig = defaultAirportConfig.copy(
              terminalPaxSplits = Map(T1 -> SplitRatios(
                SplitSources.TerminalAverage,
                SplitRatio(eeaMachineReadableToDesk, 1),
                SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.Transfer), 1)
              )),
              terminalProcessingTimes = Map(T1 -> Map(eeaMachineReadableToDesk -> oneMinute)),
              queuesByTerminal = SortedMap(T1 -> Seq(EeaDesk))
            )))

          offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

          val expected = Set(Queues.EeaDesk)

          crunch.portStateTestProbe.fishForMessage(1.seconds) {
            case ps: PortState =>
              val resultSummary = paxLoadsFromPortState(ps, 1).flatMap(_._2.keys).toSet
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

        val flights = Seq(
          ArrivalGenerator.live(schDt = scheduled, iata = "BA0001", terminal = T1, totalPax = Option(15)),
          ArrivalGenerator.live(schDt = scheduled, iata = "FR8819", terminal = InvalidTerminal, totalPax = Option(10))
        )

        val crunch = runCrunchGraph(TestConfig(
          now = () => SDate(scheduled),
          airportConfig = defaultAirportConfig.copy(
            terminalProcessingTimes = Map(T1 -> Map(eeaMachineReadableToDesk -> oneMinute)),
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
