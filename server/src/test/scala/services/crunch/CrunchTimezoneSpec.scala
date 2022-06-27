package services.crunch

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.Flights
import drt.shared._
import server.feeds.ArrivalsFeedSuccess
import services.SDate
import services.crunch.deskrecs.DeskRecs
import services.graphstages.Crunch._
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues.eeaMachineReadableToDesk
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports.{PaxTypeAndQueue, Queues}

import scala.collection.immutable.List
import scala.concurrent.duration._


class CrunchTimezoneSpec extends CrunchTestLike {
  "Crunch timezone " >> {
    "Given an SDateLike for a date outside BST" +
      "When I ask for a corresponding crunch start time " +
      "Then I should get an SDateLike representing the previous midnight UTC" >> {
      val now = SDate("2010-01-02T11:39", europeLondonTimeZone)

      val result = now.getLocalLastMidnight.millisSinceEpoch
      val expected = SDate("2010-01-02T00:00").millisSinceEpoch

      result === expected
    }

    "Given an SDateLike for a date inside BST" +
      "When I ask for a corresponding crunch start time " +
      "Then I should get an SDateLike representing the previous midnight UTC" >> {
      val now = SDate("2010-07-02T11:39", europeLondonTimeZone)
      val result: MillisSinceEpoch = now.getLocalLastMidnight.millisSinceEpoch
      val expected = SDate("2010-07-01T23:00").millisSinceEpoch

      result === expected
    }

    "Min / Max desks in BST " >> {
      "Given flights with one passenger and one split to eea desk " >> {
        "When the date falls within BST " >> {
          "Then I should see min desks allocated in alignment with BST" >> {
            val minMaxDesks: Map[Terminal, Map[Queue, (List[Int], List[Int])]] = Map(
              T1 -> Map(
                Queues.EeaDesk -> Tuple2(0 :: 5 :: List.fill[Int](22)(0), List.fill[Int](24)(20)),
                Queues.NonEeaDesk -> Tuple2(0 :: 5 :: List.fill[Int](22)(0), List.fill[Int](24)(20))
              )
            )

            val scheduledDuringBst = "2017-06-01T00:00Z"

            val flights = Flights(List(
              ArrivalGenerator.arrival(schDt = scheduledDuringBst, iata = "BA0001", terminal = T1, actPax = Option(1))
            ))

            val fiveMinutes = 600d / 60
            val procTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]] = Map(T1 -> Map(eeaMachineReadableToDesk -> fiveMinutes))

            val crunch = runCrunchGraph(TestConfig(
              now = () => SDate(scheduledDuringBst),
              airportConfig = defaultAirportConfig.copy(
                minMaxDesksByTerminalQueue24Hrs = minMaxDesks,
                terminalProcessingTimes = procTimes,
                queuesByTerminal = defaultAirportConfig.queuesByTerminal.filterKeys(_ == T1),
                minutesToCrunch = 120
              )))

            offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

            crunch.portStateTestProbe.fishForMessage(5 seconds) {
              case ps: PortState =>
                val midnightBstEeaFiveDesks = ps.crunchMinutes.values.exists(cm => cm.minute == SDate("2017-05-31T23:00").millisSinceEpoch && cm.deskRec == 0)
                val oneAmBstEeaZeroDesks = ps.crunchMinutes.values.exists(cm => cm.minute == SDate("2017-06-01T00:00").millisSinceEpoch && cm.deskRec == 5)
                midnightBstEeaFiveDesks && oneAmBstEeaZeroDesks
            }

            success
          }
        }
      }

      "Given a list of Min or Max desks" >> {
        "When parsing a BST date then we should get BST min/max desks" >> {
          val testMaxDesks = IndexedSeq(0, 1, 2, 3, 4, 5)
          val startTimeMidnightBST = SDate("2017-06-01T00:00Z").addHours(-1).millisSinceEpoch

          val oneHour = oneMinuteMillis * 60
          val startTimes = startTimeMidnightBST to startTimeMidnightBST + (oneHour * 5) by oneHour

          val expected = IndexedSeq(0, 1, 2, 3, 4, 5)
          startTimes.map(DeskRecs.desksForHourOfDayInUKLocalTime(_, testMaxDesks)) === expected
        }
        "When parsing a GMT date then we should get BST min/max desks" >> {
          val testMaxDesks = IndexedSeq(0, 1, 2, 3, 4, 5)
          val startTimeMidnightGMT = SDate("2017-01-01T00:00Z").millisSinceEpoch

          val oneHour = oneMinuteMillis * 60
          val startTimes = startTimeMidnightGMT to startTimeMidnightGMT + (oneHour * 5) by oneHour

          val expected = IndexedSeq(0, 1, 2, 3, 4, 5)
          startTimes.map(DeskRecs.desksForHourOfDayInUKLocalTime(_, testMaxDesks)) === expected
        }
      }
    }
  }
}
