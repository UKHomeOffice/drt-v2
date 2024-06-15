package slickdb.dao

import slickdb.AggregatedDbTables
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.{SDate, SDateLike, UtcDate}

import java.sql.Timestamp
import scala.concurrent.Future

case class AggregatedDao(db: AggregatedDbTables,
                         now: () => SDateLike,
                         portCode: PortCode,
                        ) {

  import db.profile.api._

  val deleteArrivalsBefore: UtcDate => Future[Int] = date => {
    val q = db.arrival
      .filter { s =>
        s.scheduled < new Timestamp(SDate(date).millisSinceEpoch) &&
          s.destination === portCode.iata.toUpperCase
      }
      .delete

    db.run(q)
  }
}
