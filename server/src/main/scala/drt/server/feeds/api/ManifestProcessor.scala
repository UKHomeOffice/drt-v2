package drt.server.feeds.api

import drt.server.feeds.{DqManifests, ManifestsFeedResponse, ManifestsFeedSuccess}
import drt.shared.CrunchApi.MillisSinceEpoch
import org.apache.pekko.Done
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import uk.gov.homeoffice.drt.models._

import scala.concurrent.{ExecutionContext, Future}





trait ManifestProcessor {
  def process(uniqueArrivalKeys: Seq[UniqueArrivalKey], processedAt: MillisSinceEpoch): Future[Done]

  def reportNoNewData(processedAt: MillisSinceEpoch): Future[Done]
}

case class DbManifestProcessor(manifestForArrivalKey: UniqueArrivalKey => Future[Option[VoyageManifest]],
                               persistManifests: ManifestsFeedResponse => Future[Done],
                              )
                              (implicit ec: ExecutionContext, mat: Materializer) extends ManifestProcessor {
  override def reportNoNewData(processedAt: MillisSinceEpoch): Future[Done] =
    persistManifests(ManifestsFeedSuccess(DqManifests(processedAt, Seq())))

  override def process(uniqueArrivalKeys: Seq[UniqueArrivalKey], processedAt: MillisSinceEpoch): Future[Done] = {
    Source(uniqueArrivalKeys.grouped(25).toList)
      .mapAsync(1) { keys =>
        Source(keys)
          .mapAsync(1)(manifestForArrivalKey)
          .collect {
            case Some(manifest) => manifest
          }
          .runWith(Sink.seq)
          .flatMap { manifests =>
            persistManifests(ManifestsFeedSuccess(DqManifests(processedAt, manifests)))
          }
      }
      .runWith(Sink.ignore)
  }
}
