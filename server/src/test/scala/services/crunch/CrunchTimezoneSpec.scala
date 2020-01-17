package services.crunch

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues.eeaMachineReadableToDesk
import drt.shared.Queues.Queue
import drt.shared.Terminals.{T1, Terminal}
import drt.shared._
import server.feeds.ArrivalsFeedSuccess
import services.SDate
import services.graphstages.Crunch._

import scala.collection.immutable.{List, Seq}
import scala.concurrent.duration._


class CrunchTimezoneSpec extends CrunchTestLike {
  "Crunch timezone " >> {
    "Given an SDateLike for a date outside BST" +
      "When I ask for a corresponding crunch start time " +
      "Then I should get an SDateLike representing the previous midnight UTC" >> {
      val now = SDate("2010-01-02T11:39", europeLondonTimeZone)

      val result = getLocalLastMidnight(now).millisSinceEpoch
      val expected = SDate("2010-01-02T00:00").millisSinceEpoch

      result === expected
    }

    "Given an SDateLike for a date inside BST" +
      "When I ask for a corresponding crunch start time " +
      "Then I should get an SDateLike representing the previous midnight UTC" >> {
      val now = SDate("2010-07-02T11:39", europeLondonTimeZone)
      val result: MillisSinceEpoch = getLocalLastMidnight(now).millisSinceEpoch
      val expected = SDate("2010-07-01T23:00").millisSinceEpoch

      result === expected
    }

    "Min / Max desks in BST " >> {
      "Given flights with one passenger and one split to eea desk " +
        "When the date falls within BST " +
        "Then I should see min desks allocated in alignment with BST" >> {
        val minMaxDesks: Map[Terminal, Map[Queue, (List[Int], List[Int])]] = Map(
          T1 -> Map(
            Queues.EeaDesk -> Tuple2(0 :: 5 :: List.fill[Int](22)(0), List.fill[Int](24)(20)),
            Queues.NonEeaDesk -> Tuple2(0 :: 5 :: List.fill[Int](22)(0), List.fill[Int](24)(20))
          )
        )

        val scheduled = "2017-06-01T00:00Z"

        val flights = Flights(List(
          ArrivalGenerator.arrival(schDt = scheduled, iata = "BA0001", terminal = T1, actPax = Option(1))
        ))

        val fiveMinutes = 600d / 60
        val procTimes: Map[Terminal, Map[PaxTypeAndQueue, Double]] = Map(T1 -> Map(eeaMachineReadableToDesk -> fiveMinutes))

        val crunch = runCrunchGraph(
          now = () => SDate(scheduled),
          airportConfig = defaultAirportConfig.copy(
            minMaxDesksByTerminalQueue = minMaxDesks,
            terminalProcessingTimes = procTimes,
            queuesByTerminal = defaultAirportConfig.queuesByTerminal.filterKeys(_ == T1)
          ),
          minutesToCrunch = 120)

        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(flights))

        val expected = Map(T1 -> Map(
          Queues.EeaDesk -> List(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
            5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5
          ),
          Queues.NonEeaDesk -> List(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
            5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5
          )
        ))

        crunch.portStateTestProbe.fishForMessage(5 seconds) {
          case ps: PortState =>
            val resultSummary = deskRecsFromPortState(ps, 120)
            println(s"got $resultSummary")
            resultSummary == expected
        }

        crunch.shutdown

        success
      }

      "Given a list of Min or Max desks" >> {
        "When parsing a BST date then we should get BST min/max desks" >> {
          val testMaxDesks = List(0, 1, 2, 3, 4, 5)
          val startTimeMidnightBST = SDate("2017-06-01T00:00Z").addHours(-1).millisSinceEpoch

          val oneHour = oneMinuteMillis * 60
          val startTimes = startTimeMidnightBST to startTimeMidnightBST + (oneHour * 5) by oneHour

          val expected = List(0, 1, 2, 3, 4, 5)
          startTimes.map(desksForHourOfDayInUKLocalTime(_, testMaxDesks)) === expected
        }
        "When parsing a GMT date then we should get BST min/max desks" >> {
          val testMaxDesks = List(0, 1, 2, 3, 4, 5)
          val startTimeMidnightGMT = SDate("2017-01-01T00:00Z").millisSinceEpoch

          val oneHour = oneMinuteMillis * 60
          val startTimes = startTimeMidnightGMT to startTimeMidnightGMT + (oneHour * 5) by oneHour

          val expected = List(0, 1, 2, 3, 4, 5)
          startTimes.map(desksForHourOfDayInUKLocalTime(_, testMaxDesks)) === expected
        }
      }
    }
  }
}
