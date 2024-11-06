package services.exports

import akka.actor.ActorSystem
import akka.stream.Materializer
import controllers.ArrivalGenerator
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import services.api.v1.FlightExport
import services.api.v1.FlightExport.{FlightJson, PortFlightsJson, TerminalFlightsJson}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports.{FeedSource, LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContextExecutor, Future}


class FlightExportSpec extends AnyWordSpec with Matchers {
  implicit val system: ActorSystem = ActorSystem("FlightExportSpec")
  implicit val mat: Materializer = Materializer.matFromSystem
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val sourceOrderPreference: List[FeedSource] = List(LiveFeedSource)

  val startMinute: SDateLike = SDate("2024-10-15T12:00")
  val endMinute: SDateLike = SDate("2024-10-15T14:00")

  "FlightExport" should {
    "return a PortFlightsJson with the correct structure and only the flight with passengers in the requested time range" in {
      val source = (_: SDateLike, _: SDateLike, _: Terminal) => {
        Future.successful(Seq(
          ArrivalGenerator.arrival(iata = "BA0001", schDt = "2024-10-15T11:00", totalPax = Option(100), transPax = Option(10), feedSource = LiveFeedSource),
          ArrivalGenerator.arrival(iata = "BA0002", schDt = "2024-10-15T12:00", totalPax = Option(100), transPax = Option(10), feedSource = LiveFeedSource),
          ArrivalGenerator.arrival(iata = "BA0003", schDt = "2024-10-15T13:55", totalPax = Option(200), transPax = Option(10), feedSource = LiveFeedSource),
          ArrivalGenerator.arrival(iata = "BA0004", schDt = "2024-10-15T15:00", totalPax = Option(200), transPax = Option(10), feedSource = LiveFeedSource),
        ))
      }
      val export = FlightExport(source, Seq(T1), PortCode("LHR"))
      val sched1 = SDate("2024-10-15T12:00")
      val sched2 = SDate("2024-10-15T13:55")
      Await.result(export(startMinute, endMinute), 1.second) shouldEqual
        PortFlightsJson(
          PortCode("LHR"),
          List(TerminalFlightsJson(
            T1,
            List(
              FlightJson("BA0002", "JFK", sched1.millisSinceEpoch, Some(sched1.addMinutes(5).millisSinceEpoch), Some(sched1.addMinutes(9).millisSinceEpoch), Some(90), ""),
              FlightJson("BA0003", "JFK", sched2.millisSinceEpoch, Some(sched2.addMinutes(5).millisSinceEpoch), Some(sched2.addMinutes(14).millisSinceEpoch), Some(190), ""),
            )
          )
          )
        )
    }
  }
}
