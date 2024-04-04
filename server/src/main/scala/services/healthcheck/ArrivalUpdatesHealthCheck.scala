package services.healthcheck

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}

import scala.concurrent.ExecutionContext


case class ArrivalUpdatesHealthCheck(flights: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
                                     now: () => SDateLike,
                                    )
                                    (maxMinutesSinceLastUpdate: Int)
                                    (implicit val ec: ExecutionContext, val mat: Materializer) extends PercentageHealthCheck {
  override val healthyCount: Seq[ApiFlightWithSplits] => Int = _.count { f =>
    val updateThreshold = now().addMinutes(-maxMinutesSinceLastUpdate).millisSinceEpoch
    f.lastUpdated.exists(lu => lu >= updateThreshold)
  }
}
