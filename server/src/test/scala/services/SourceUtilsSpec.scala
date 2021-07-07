package services

import actors.ArrivalGenerator
import actors.routing.FlightsRouterActor
import akka.stream.scaladsl.Sink
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals._
import drt.shared.dates.UtcDate
import drt.shared.{ApiFlightWithSplits, PortCode}
import services.SourceUtils.applyFutureIterablesReducer
import services.crunch.CrunchTestLike

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

  def stuffForTerminalDate(d: Int)(t: Terminal): Future[List[String]] = {
    Future.successful(flights.getOrElse((t, d), List()))
  }

  val reducer: Iterable[List[String]] => List[String] = _.reduce(_ ++ _)

  "sortedSourceForIterables" should {
    "produce combined lists strings for each date requested when given a simple reducer" in {
      val terminals: Seq[Terminal] = List(T2, T3, T4, T5)

      val dates = List(10, 11, 12, 13)

      val value = applyFutureIterablesReducer(dates, stuffForTerminalDate, SourceUtils.reduceFutureIterables(terminals, reducer))

      val result = Await.result(value.runWith(Sink.seq), 1.second)

      result === Vector(List("d", "a", "b", "c"), List("z", "y", "x", "w"), List("s", "q", "t", "r"), List())
    }
  }

  "sortedSourceForIterables" should {
    "produce a FlightsWithSplits for each date, with flights from all terminals sorted by pcp time & voyage number" in {
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
      val flights: Map[(Terminal, UtcDate), FlightsWithSplits] = Map(
        (T2, UtcDate(2021, 7, 10)) -> FlightsWithSplits(List(t21015, t21013)),
        (T3, UtcDate(2021, 7, 10)) -> FlightsWithSplits(List(t31015, t31013)),
        (T2, UtcDate(2021, 7, 11)) -> FlightsWithSplits(List(t21115, t21113)),
        (T3, UtcDate(2021, 7, 11)) -> FlightsWithSplits(List(t31115, t31113)),
      )

      def stuffForTerminalDate(d: UtcDate)(t: Terminal): Future[FlightsWithSplits] =
        Future.successful(flights.getOrElse((t, d), FlightsWithSplits.empty))

      val value = applyFutureIterablesReducer(dates, stuffForTerminalDate, SourceUtils.reduceFutureIterables(terminals, FlightsRouterActor.reducer))

      val result = Await.result(value.runWith(Sink.seq), 1.second)

      result === Seq(FlightsWithSplits(Seq(t21013, t31013, t21015, t31015)), FlightsWithSplits(Seq(t21113, t31113, t21115, t31115)))
    }
  }
}
