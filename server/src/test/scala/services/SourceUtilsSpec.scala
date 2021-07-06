package services

import actors.ArrivalGenerator
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.{ApiFlightWithSplits, PortCode}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.{T2, T3, T4, T5, Terminal}
import drt.shared.dates.UtcDate
import org.specs2.mutable.Specification
import services.SourceUtils.flatMapIterableStreams
import services.crunch.CrunchTestLike

import scala.collection.immutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class SourceUtilsSpec extends CrunchTestLike {
  val flights: Map[(Terminal, Int), List[String]] = Map(
    (T2, 10) -> List("d", "a"),
    (T3, 10) -> List("b", "c"),
    (T2, 11) -> List("z", "y"),
    (T3, 11) -> List("x", "w"),
    (T2, 12) -> List("s", "q"),
    (T3, 12) -> List("t", "r"),
  )

  def stuffForTerminalDate(d: Int, t: Terminal): Future[Source[String, NotUsed]] = {
    Future.successful(Source(flights.getOrElse((t, d), List())))
  }

  val transform: immutable.Seq[String] => immutable.Seq[String] = _.sorted

  "sortedSourceForIterables" should {
    "produce a stream ordered within the 2ns iterable" in {
      val terminals: Seq[Terminal] = List(T2, T3, T4, T5)

      val dates = List(10, 11, 12, 13)

      val value: Source[String, NotUsed] = flatMapIterableStreams(dates, terminals, stuffForTerminalDate, transform)

      val result = Await.result(value.runWith(Sink.seq), 1.second)

      result === Seq("a", "b", "c", "d", "w", "x", "y", "z", "q", "r", "s", "t")
    }
  }

  "sortedSourceForIterables" should {
    "produce a stream of flights ordered by pcp time and voyage number within each requested date" in {
      val terminals: Seq[Terminal] = List(T2, T3, T4, T5)

      val dates = List(UtcDate(2021, 7, 10), UtcDate(2021, 7, 11))

      val t21015 = ApiFlightWithSplits(ArrivalGenerator.arrival(pcpDt = "2021-07-10T15:00", iata = "BA0001", origin = PortCode("JFK"), terminal = T2), Set())
      val t21013 = ApiFlightWithSplits(ArrivalGenerator.arrival(pcpDt = "2021-07-10T13:00", iata = "BA0002", origin = PortCode("JFK"), terminal = T2), Set())
      val t31015 = ApiFlightWithSplits(ArrivalGenerator.arrival(pcpDt = "2021-07-10T15:00", iata = "BA0003", origin = PortCode("JFK"), terminal = T3), Set())
      val t31013 = ApiFlightWithSplits(ArrivalGenerator.arrival(pcpDt = "2021-07-10T13:00", iata = "BA0004", origin = PortCode("JFK"), terminal = T3), Set())
      val t21115 = ApiFlightWithSplits(ArrivalGenerator.arrival(pcpDt = "2021-07-10T15:00", iata = "BA0005", origin = PortCode("JFK"), terminal = T2), Set())
      val t21113 = ApiFlightWithSplits(ArrivalGenerator.arrival(pcpDt = "2021-07-10T13:00", iata = "BA0006", origin = PortCode("JFK"), terminal = T2), Set())
      val t31115 = ApiFlightWithSplits(ArrivalGenerator.arrival(pcpDt = "2021-07-10T15:00", iata = "BA0007", origin = PortCode("JFK"), terminal = T3), Set())
      val t31113 = ApiFlightWithSplits(ArrivalGenerator.arrival(pcpDt = "2021-07-10T13:00", iata = "BA0008", origin = PortCode("JFK"), terminal = T3), Set())
      val flights: Map[(Terminal, UtcDate), List[ApiFlightWithSplits]] = Map(
        (T2, UtcDate(2021, 7, 10)) -> List(t21015, t21013),
        (T3, UtcDate(2021, 7, 10)) -> List(t31015, t31013),
        (T2, UtcDate(2021, 7, 11)) -> List(t21115, t21113),
        (T3, UtcDate(2021, 7, 11)) -> List(t31115, t31113),
      )

      def stuffForTerminalDate(d: UtcDate, t: Terminal): Future[Source[ApiFlightWithSplits, NotUsed]] =
        Future.successful(Source(flights.getOrElse((t, d), List())))

      val transform: immutable.Seq[ApiFlightWithSplits] => immutable.Seq[ApiFlightWithSplits] = (flights: immutable.Seq[ApiFlightWithSplits]) => flights.sortBy { fws =>
        val arrival = fws.apiFlight
        (arrival.PcpTime, arrival.VoyageNumber.numeric, arrival.Origin.iata)
      }

      val value = flatMapIterableStreams(dates, terminals, stuffForTerminalDate, transform)

      val result = Await.result(value.runWith(Sink.seq), 1.second)

      result === Seq(t21013, t31013, t21015, t31015, t21113, t31113, t21115, t31115)
    }
  }
}
