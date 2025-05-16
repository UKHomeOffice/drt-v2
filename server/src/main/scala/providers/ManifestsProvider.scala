package providers

import actors.persistent.ManifestRouterActor
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.Timeout
import uk.gov.homeoffice.drt.models.VoyageManifests
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

object ManifestsProvider {
  def apply(manifestsRouterActor: ActorRef)
           (implicit timeout: Timeout): (UtcDate, UtcDate) => Source[(UtcDate, VoyageManifests), NotUsed] =
    (start, end) => {
      val startMillis = SDate(start).millisSinceEpoch
      val endMillis = SDate(end).addDays(1).addMinutes(-1).millisSinceEpoch
      Source.future(
        manifestsRouterActor
          .ask(ManifestRouterActor.GetManifestsForDateRange(startMillis, endMillis))
          .mapTo[Source[(UtcDate, VoyageManifests), NotUsed]]
      ).flatMapConcat(identity)
    }
}
