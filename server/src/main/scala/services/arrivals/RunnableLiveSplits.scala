package services.arrivals

import drt.shared.CrunchApi.MillisSinceEpoch
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.stream._
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.models.UniqueArrivalKey
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

import scala.concurrent.{ExecutionContext, Future}


object RunnableLiveSplits extends RunnableGraphLike {
  private val log = LoggerFactory.getLogger(getClass)

  def apply(arrivalKeysForDate: UtcDate => Future[Seq[UniqueArrivalKey]],
            updateSplitsForArrivalKeys: (Seq[UniqueArrivalKey], MillisSinceEpoch) => Future[Done],
           )
           (implicit ec: ExecutionContext, mat: Materializer): (ActorRef, UniqueKillSwitch) = {
    val flow = Flow[UtcDate]
      .mapAsync(1) { date =>
        log.info(s"Looking up live splits for ${date.toISOString}")
        val startTime = SDate.now().millisSinceEpoch
        Source.future(arrivalKeysForDate(date))
          .mapAsync(1) { arrivalKeys => updateSplitsForArrivalKeys(arrivalKeys, SDate.now().millisSinceEpoch) }
          .runWith(Sink.seq)
          .map { _ =>
            log.info(s"Updated live splits for ${date.toISOString} in ${SDate.now().millisSinceEpoch - startTime}ms")
            Done
          }
      }

    constructAndRunGraph(flow)
  }
}
