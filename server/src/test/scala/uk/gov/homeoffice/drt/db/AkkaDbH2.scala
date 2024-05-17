package uk.gov.homeoffice.drt.db

import slick.jdbc.JdbcProfile
import slick.dbio.{DBIOAction, NoStream}
import slickdb.AggregatedDbTables

import scala.concurrent.Future

object AkkaDbH2 extends AggregatedDbTables {
  override val profile: JdbcProfile = slick.jdbc.H2Profile
  val db: profile.backend.Database = profile.api.Database.forConfig("h2-akka-db")

  override def run[R](action: DBIOAction[R, NoStream, Nothing]): Future[R] = db.run[R](action)
}
