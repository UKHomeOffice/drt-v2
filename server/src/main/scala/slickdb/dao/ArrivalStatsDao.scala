package slickdb.dao

import slickdb.{AggregatedDbTables, ArrivalStatsRow}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.{ExecutionContext, Future}

case class ArrivalStatsDao(db: AggregatedDbTables,
                           now: () => SDateLike,
                           portCode: PortCode,
                          )
                          (implicit ec: ExecutionContext) {

  import db.profile.api._

  val get: (String, String, Int, String) => Future[Option[ArrivalStatsRow]] =
    (terminal, date, daysAhead, dataType) => {
      val q = db.arrivalStats
        .filter { s =>
          s.portCode === portCode.iata &&
            s.terminal === terminal &&
            s.date === date &&
            s.daysAhead === daysAhead &&
            s.dataType === dataType
        }
        .take(1)
        .result
        .map(_.headOption)

      db.run(q)
    }

  val addOrUpdate: ArrivalStatsRow => Future[Int] = row => {
    db.run(db.arrivalStats.insertOrUpdate(row))
  }
}
