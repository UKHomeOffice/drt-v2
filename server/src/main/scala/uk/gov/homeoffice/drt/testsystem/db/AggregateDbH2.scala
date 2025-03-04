package uk.gov.homeoffice.drt.testsystem.db

import slick.dbio.{DBIOAction, NoStream}
import slick.jdbc.JdbcProfile
import slickdb.AggregatedDbTables

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

object AggregateDbH2 extends AggregatedDbTables {
  override val profile: JdbcProfile = slick.jdbc.H2Profile
  val db: profile.backend.Database = profile.api.Database.forConfig("h2-aggregated-db")

  override def run[R](action: DBIOAction[R, NoStream, Nothing]): Future[R] = db.run[R](action)


  def dropAndCreateH2Tables()
                           (implicit ec: ExecutionContext): Unit = {

    import profile.api._

    Await.result(
      run(DBIO.seq(tables.map(_.schema.dropIfExists): _*))
        .flatMap(_ => run(DBIO.seq(tables.map(_.schema.create): _*))),
      1.second
    )
  }
}
