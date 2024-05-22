package uk.gov.homeoffice.drt.testsystem.db

import slick.dbio.{DBIOAction, NoStream}
import slick.jdbc.JdbcProfile
import slickdb.AggregatedDbTables

import scala.concurrent.Future

object AggregateDbH2 extends AggregatedDbTables {
  override val profile: JdbcProfile = slick.jdbc.H2Profile
  val db: profile.backend.Database = profile.api.Database.forConfig("h2-aggregated-db")

  override def run[R](action: DBIOAction[R, NoStream, Nothing]): Future[R] = db.run[R](action)
}
