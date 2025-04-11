package services.healthcheck

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import uk.gov.homeoffice.drt.time.UtcDate

import scala.concurrent.ExecutionContext


case class ApiHealthCheck(flights: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed])
                         (implicit val ec: ExecutionContext, val mat: Materializer) extends PercentageHealthCheck {
  override val healthyCount: Seq[ApiFlightWithSplits] => Int = _.count { f =>
    f.splits.exists(_.source == ApiSplitsWithHistoricalEGateAndFTPercentages)
  }
}
