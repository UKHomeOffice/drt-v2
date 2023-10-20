package services.healthcheck

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

trait PercentageHealthCheck {
  implicit val ec: ExecutionContext
  implicit val mat: Materializer

  val flights: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed]

  val healthyCount: Seq[ApiFlightWithSplits] => Int

  val minimumToConsider: Int

  def healthy(start: SDateLike, end: SDateLike): Future[Option[Double]] =
    flights(start.toUtcDate, end.toUtcDate)
      .map { case (_, flights) =>
        flights
          .filterNot(_.apiFlight.Origin.isDomesticOrCta)
          .filter { f =>
            val arrivalTime = f.apiFlight.bestArrivalTime(true)
            start.millisSinceEpoch <= arrivalTime && arrivalTime <= end.millisSinceEpoch
          }
      }
      .collect {
        case flights if flights.size >= minimumToConsider => flights
      }
      .map(flights => healthyCount(flights).toDouble / flights.size)
      .runWith(Sink.seq)
      .map(_.headOption)
}
