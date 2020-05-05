package services.`export`

import akka.actor.{ActorRef, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.{EeaDesk, Queue}
import drt.shared.Terminals.{T1, Terminal}
import drt.shared.api.Arrival
import drt.shared.{SDateLike, _}
import services.SDate
import services.crunch.CrunchTestLike
import services.exports.summaries.flights.TerminalFlightsSummary
import services.exports.summaries.{GetSummaries, TerminalSummaryLike}
import services.graphstages.Crunch

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class FlightsExportSpec extends CrunchTestLike {
  val terminal: Terminal = T1

  val year = 2020
  val month = 1
  val day = 1
  val from: SDateLike = SDate(year, month, day, 0, 0, Crunch.europeLondonTimeZone)
  val queues = Seq(EeaDesk)
  val someFlights = Seq(ApiFlightWithSplits(ArrivalGenerator.arrival("BA0001", schDt = "2020-01-01T00:00", actPax = Option(100)), Set()))

  import services.exports.Exports._

  def pcpPaxFn: Arrival => Int = PcpPax.bestPaxEstimateWithApi

  "Given a flights summary actor for a given day which does not have any persisted data for that day and there is a port state available" >> {
    "When I ask for terminal flight summaries for that day" >> {
      "I should get back the flights from the port state" >> {
        val mockTerminalSummariesActor = system.actorOf(Props(classOf[MockTerminalSummariesActor], None, None))
        val noCrunchMinutes = Iterable()
        val noStaffMinutes = Iterable()
        val portState = PortState(someFlights, noCrunchMinutes, noStaffMinutes)

        val portStateToSummaries = flightSummariesFromPortState(TerminalFlightsSummary.generator)(terminal, pcpPaxFn, (_, _) => Future(FlightsWithSplits(portState.flights))) _
        val result = Await.result(historicSummaryForDay(from, mockTerminalSummariesActor, GetSummaries, portStateToSummaries), 1 second)
          .asInstanceOf[TerminalFlightsSummary].flights

        val expected = someFlights

        result == expected
      }
    }
  }

  "Given a flights summary actor for a given day which does have some persisted data" >> {
    "When I ask for terminal summaries for that day" >> {
      "I should get back the persisted summaries" >> {
        val persistedSummaries = TerminalFlightsSummary(someFlights, millisToLocalIsoDateOnly, millisToLocalHoursAndMinutes, pcpPaxFn)
        val mockTerminalSummariesActor = system.actorOf(Props(classOf[MockTerminalSummariesActor], Option(persistedSummaries), None))

        val portStateToSummaries = flightSummariesFromPortState(TerminalFlightsSummary.generator)(terminal, pcpPaxFn, (_, _) => Future(FlightsWithSplits.empty)) _
        val result = Await.result(historicSummaryForDay(from, mockTerminalSummariesActor, GetSummaries, portStateToSummaries), 1 second)

        result === persistedSummaries
      }
    }
  }

  "Given a flights summary actor for a given day which does not have any persisted data for that day and there is a port state available" >> {
    val portState = PortState(someFlights, Iterable(), Iterable())

    "When I ask for terminal flight summaries for that day" >> {
      val portStateToSummaries = flightSummariesFromPortState(TerminalFlightsSummary.generator)(terminal, pcpPaxFn, (_, _) => Future(FlightsWithSplits(portState.flights))) _

      def eventualMaybeSummaries(actorProbe: ActorRef): Future[TerminalSummaryLike] = {
        historicSummaryForDay(from, actorProbe, GetSummaries, portStateToSummaries)
      }

      "I should get back 96 summaries including one generated from the crunch & staff minutes in the port state" >> {
        val mockTerminalSummariesActor = system.actorOf(Props(classOf[MockTerminalSummariesActor], None, None))
        val result = Await.result(eventualMaybeSummaries(mockTerminalSummariesActor), 1 second).asInstanceOf[TerminalFlightsSummary]
        val expected = TerminalFlightsSummary(someFlights, millisToLocalIsoDateOnly, millisToLocalHoursAndMinutes, pcpPaxFn)

        result === expected
      }

      "I should see the generated summaries sent to the summary actor for persistence" >> {
        val summariesProbe = TestProbe("summariesprobe")
        val mockTerminalSummariesActor = system.actorOf(Props(classOf[MockTerminalSummariesActor], None, Option(summariesProbe.ref)))
        Await.ready(eventualMaybeSummaries(mockTerminalSummariesActor), 1 second)

        summariesProbe.expectMsgClass(classOf[TerminalFlightsSummary])

        success
      }
    }
  }

  "Given a flights summary actor for a given day which does not have any persisted data for that day and there is a port state available, but it contains no data" >> {
    val portState = PortState(Iterable(), Iterable(), Iterable())

    "When I ask for terminal flight summaries for that day" >> {
      val portStateToSummaries = flightSummariesFromPortState(TerminalFlightsSummary.generator)(terminal, pcpPaxFn, (_, _) => Future(FlightsWithSplits(portState.flights))) _

      def eventualMaybeSummaries(actorProbe: ActorRef): Future[TerminalSummaryLike] = {
        historicSummaryForDay(from, actorProbe, GetSummaries, portStateToSummaries)
      }

      "I should not see the generated summaries sent to the summary actor for persistence" >> {
        val summariesProbe = TestProbe("summariesprobe")
        val mockTerminalSummariesActor = system.actorOf(Props(classOf[MockTerminalSummariesActor], None, Option(summariesProbe.ref)))
        Await.ready(eventualMaybeSummaries(mockTerminalSummariesActor), 1 second)

        summariesProbe.expectNoMessage(2 seconds)

        success
      }
    }
  }

  "Given a range of dates, and some mock summary actors containing data for those dates" >> {
    def persistedSummaries(queues: Seq[Queue], from: SDateLike) = TerminalFlightsSummary(someFlights.map { fws =>
      val arrival = fws.apiFlight.copy(Scheduled = from.millisSinceEpoch)
      fws.copy(apiFlight = arrival)
    }, millisToLocalIsoDateOnly, millisToLocalHoursAndMinutes, pcpPaxFn)

    def mockTerminalSummariesActor: (SDateLike, Terminal) => ActorRef = (from: SDateLike, _: Terminal) => system.actorOf(Props(classOf[MockTerminalSummariesActor], Option(persistedSummaries(Seq(EeaDesk), from)), None))

    "When I ask for the summary data for the range of dates" >> {
      "Then I should see each date's mock actor's summary data" >> {
        val summaryActorProvider = mockTerminalSummariesActor

        val now: () => SDateLike = () => SDate("2020-06-01")
        val startDate = SDate("2020-01-01T00:00", Crunch.europeLondonTimeZone)
        val portStateToSummaries = flightSummariesFromPortState(TerminalFlightsSummary.generator)(terminal, pcpPaxFn, (_, _) => Future(FlightsWithSplits.empty)) _

        val exportStream = summaryForDaysCsvSource(startDate, 3, now, terminal, Option((summaryActorProvider, GetSummaries)), portStateToSummaries)

        val value1 = exportStream.runWith(Sink.seq)(ActorMaterializer())
        val result = Await.result(value1, 1 second)

        val expected = List(
          persistedSummaries(queues, SDate("2020-01-01")).toCsvWithHeader,
          persistedSummaries(queues, SDate("2020-01-02")).toCsv,
          persistedSummaries(queues, SDate("2020-01-03")).toCsv
        )

        result === expected
      }
    }
  }
}
