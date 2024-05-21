package uk.gov.homeoffice.drt.db

import slick.dbio.{DBIOAction, NoStream}
import slickdb._

import scala.concurrent.Future

object AkkaDb extends AkkaDbTables {
  override val profile = slick.jdbc.PostgresProfile
  val db: profile.backend.Database = profile.api.Database.forConfig("slick.db")

  override def run[R](action: DBIOAction[R, NoStream, Nothing]): Future[R] = db.run[R](action)
}
