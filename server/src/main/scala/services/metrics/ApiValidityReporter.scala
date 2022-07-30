package services.metrics

import actors.PartitionedPortStateActor.GetStateForDateRange
import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.FlightsApi.FlightsWithSplits
import services.SDate
import services.crunch.deskrecs.DynamicRunnableDeskRecs
import uk.gov.homeoffice.drt.time.UtcDate

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
          Metrics.counter("api-live-valid-percentage", DynamicRunnableDeskRecs.validApiPercentage(flights))
      })
  }
}
