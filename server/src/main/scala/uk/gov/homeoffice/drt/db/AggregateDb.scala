package uk.gov.homeoffice.drt.db

import org.slf4j.LoggerFactory
import slick.dbio.{DBIOAction, NoStream}
import slickdb._

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object AggregateDb extends AggregatedDbTables {
  private val log = LoggerFactory.getLogger(getClass)

  override val profile = slick.jdbc.PostgresProfile
  val db: profile.backend.Database = Try(profile.api.Database.forConfig("aggregated-db")) match {
    case Success(database) => database
    case Failure(exception) =>
      log.error("Failed to connect to the database", exception)
      throw new RuntimeException("Database connection failed", exception)
  }

  override def run[R](action: DBIOAction[R, NoStream, Nothing]): Future[R] = db.run[R](action)
}
