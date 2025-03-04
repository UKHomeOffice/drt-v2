package services.liveviews

import org.slf4j.LoggerFactory
import slick.dbio.DBIO
import slickdb.AggregatedDbTables
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.db.dao.FlightDao
import uk.gov.homeoffice.drt.ports.PortCode

import scala.concurrent.{ExecutionContext, Future}

object FlightsLiveView {
  private val log = LoggerFactory.getLogger(getClass)

  def updateFlightsLiveView(flightDao: FlightDao,
                            aggregatedDb: AggregatedDbTables,
                            portCode: PortCode,
                           )
                           (implicit ec: ExecutionContext): (Iterable[ApiFlightWithSplits], Iterable[UniqueArrival]) => Future[Unit] = {
    val batchInsertOrUpdate = flightDao.insertOrUpdateMulti(portCode)
    val remove = flightDao.removeMulti(portCode)

    (updates, removals) =>
      aggregatedDb
        .run(DBIO.seq(batchInsertOrUpdate(updates), remove(removals)))
        .recover { case e: Throwable =>
          log.error(s"Error updating FlightsLiveView: ${e.getMessage}")
        }
  }
}
