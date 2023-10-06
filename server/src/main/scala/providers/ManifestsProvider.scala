package providers

import actors.PartitionedPortStateActor
import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.Timeout
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

object ManifestsProvider {
  def apply(manifestsRouterActor: ActorRef)
           (implicit timeout: Timeout): (UtcDate, UtcDate) => Source[(UtcDate, VoyageManifests), NotUsed] =
    (start, end) => {
      val startMillis = SDate(start).millisSinceEpoch
      val endMillis = SDate(end).addDays(1).addMinutes(-1).millisSinceEpoch
      Source.future(
        manifestsRouterActor
          .ask(PartitionedPortStateActor.GetStateForDateRange(startMillis, endMillis))
          .mapTo[Source[(UtcDate, VoyageManifests), NotUsed]]
      ).flatMapConcat(identity)
    }
}
