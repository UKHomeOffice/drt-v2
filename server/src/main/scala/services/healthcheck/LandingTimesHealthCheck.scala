package services.healthcheck

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.time.UtcDate

import scala.concurrent.ExecutionContext

case class LandingTimesHealthCheck(flights: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed])
                                  (implicit val ec: ExecutionContext, val mat: Materializer) extends PercentageHealthCheck {
  override val healthyCount: Seq[ApiFlightWithSplits] => Int = _.count(_.apiFlight.Actual.nonEmpty)
}
