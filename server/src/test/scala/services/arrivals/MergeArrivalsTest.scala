package services.arrivals

import drt.shared.ArrivalGenerator
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalsDiff, UniqueArrival}
import uk.gov.homeoffice.drt.ports.LiveFeedSource
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.time.{LocalDate, UtcDate}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class MergeArrivalsTest extends AnyWordSpec {
  implicit val system: ActorSystem = ActorSystem("MergeArrivalsTest")

  "processingRequestToArrivalsDiff" should {
    var callCount = 0
    val arrival = ArrivalGenerator.arrival(iata = "BA0001").toArrival(LiveFeedSource)
    val mergeArrivalsForDate: (Terminal, UtcDate) => Future[ArrivalsDiff] =
      (_: Terminal, _: UtcDate) => Future.successful(ArrivalsDiff(Iterable(arrival), Iterable.empty[UniqueArrival]))
    val setupPcpTimes: Seq[Arrival] => Future[Seq[Arrival]] =
      arrivals => Future.successful(arrivals.map(a => a.copy(PcpTime = Some(a.Scheduled))))
    val addArrivalPredictions: ArrivalsDiff => Future[ArrivalsDiff] =
      arrivalsDiff => {
        callCount += 1
        Future.successful(arrivalsDiff)
      }

    "call addArrivalPredictions when the request date is not earlier than today" in {
      val flow = MergeArrivals.processingRequestToArrivalsDiff(mergeArrivalsForDate, setupPcpTimes, addArrivalPredictions, () => LocalDate(2025, 5, 13))
      callCount = 0
      val runnable = Source(Seq(TerminalUpdateRequest(T1, LocalDate(2025, 5, 13))))
        .via(flow)
        .runWith(Sink.ignore)

      Await.ready(runnable, 1.second)

      assert(callCount == 1)
    }

    "not call addArrivalPredictions when the request date is not earlier than today" in {
      val flow = MergeArrivals.processingRequestToArrivalsDiff(mergeArrivalsForDate, setupPcpTimes, addArrivalPredictions, () => LocalDate(2025, 5, 13))
      callCount = 0
      val runnable = Source(Seq(TerminalUpdateRequest(T1, LocalDate(2025, 5, 12))))
        .via(flow)
        .runWith(Sink.ignore)

      Await.ready(runnable, 1.second)

      assert(callCount == 0)
    }
  }
}
