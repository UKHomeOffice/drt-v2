package services.metrics

import actors.PartitionedPortStateActor.GetStateForDateRange
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.Timeout
import services.crunch.deskrecs.DynamicRunnablePassengerLoads
import uk.gov.homeoffice.drt.arrivals.FlightsWithSplits
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

import scala.concurrent.ExecutionContext

case class ApiValidityReporter(flightsActor: ActorRef)
                              (implicit timeout: Timeout, mat: Materializer, ec: ExecutionContext) extends Runnable {
  def run(): Unit = {
    val today = SDate.now()
    val request = GetStateForDateRange(today.getLocalLastMidnight.millisSinceEpoch, today.getLocalNextMidnight.millisSinceEpoch)
    flightsActor
      .ask(request)
      .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
      .flatMap(_.runWith(Sink.seq).map {
        dateAndFlights =>
          val flights = dateAndFlights.flatMap(_._2.flights.values)
          Metrics.counter("api-live-valid-percentage", DynamicRunnablePassengerLoads.validApiPercentage(flights))
      })
  }
}
