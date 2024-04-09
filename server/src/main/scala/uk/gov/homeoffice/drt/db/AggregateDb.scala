package uk.gov.homeoffice.drt.db

import akka.stream.scaladsl.SourceQueueWithComplete
import drt.server.feeds.ManifestsFeedResponse
import slick.dbio.{DBIOAction, NoStream}
import slick.jdbc.JdbcProfile
import slickdb._

import scala.concurrent.Future

object AggregateDb extends Tables {
  override val profile = slick.jdbc.PostgresProfile
  val db: profile.backend.Database = profile.api.Database.forConfig("aggregated-db")

  override def run[R](action: DBIOAction[R, NoStream, Nothing]): Future[R] = db.run[R](action)
}

object AggregateDbH2 extends Tables {
  override val profile: JdbcProfile = slick.jdbc.H2Profile
  val db: profile.backend.Database = profile.api.Database.forConfig("h2-aggregated-db")

  override def run[R](action: DBIOAction[R, NoStream, Nothing]): Future[R] = db.run[R](action)
}

case class SubscribeResponseQueue(subscriber: SourceQueueWithComplete[ManifestsFeedResponse])


