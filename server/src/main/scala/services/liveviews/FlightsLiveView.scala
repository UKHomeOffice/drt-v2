package services.liveviews

import slick.dbio.DBIO
import slickdb.AggregatedDbTables
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.db.dao.FlightDao
import uk.gov.homeoffice.drt.ports.PortCode

object FlightsLiveView {
  def updateFlightsLiveView(flightDao: FlightDao,
                            aggregatedDb: AggregatedDbTables,
                            portCode: PortCode,
                           ): (Iterable[ApiFlightWithSplits], Iterable[UniqueArrival]) => Unit = {
    val batchInsertOrUpdate = flightDao.insertOrUpdateMulti(portCode)
    val remove = flightDao.removeMulti(portCode)

    (updates, removals) =>
      aggregatedDb.run(DBIO.seq(batchInsertOrUpdate(updates), remove(removals)))
  }
}
