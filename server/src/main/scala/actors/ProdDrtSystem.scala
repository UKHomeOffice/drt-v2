package actors

import akka.stream.scaladsl.SourceQueueWithComplete
import drt.server.feeds.ManifestsFeedResponse
import slick.dbio.{DBIOAction, NoStream}
import slickdb._

import scala.concurrent.Future

object PostgresTables extends Tables {
  override val profile = slick.jdbc.PostgresProfile
  val db: profile.backend.Database = profile.api.Database.forConfig("aggregated-db")

  override def run[R](action: DBIOAction[R, NoStream, Nothing]): Future[R] = db.run[R](action)
}

case class SubscribeResponseQueue(subscriber: SourceQueueWithComplete[ManifestsFeedResponse])


