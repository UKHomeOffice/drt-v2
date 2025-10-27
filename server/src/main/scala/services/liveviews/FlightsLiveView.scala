package services.liveviews

import org.apache.pekko.Done
import org.slf4j.LoggerFactory
import slick.dbio.DBIO
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.db.AggregatedDbTables
import uk.gov.homeoffice.drt.db.dao.FlightDao
import uk.gov.homeoffice.drt.ports.{AirportConfig, PortCode}
import uk.gov.homeoffice.drt.service.FeedService
import uk.gov.homeoffice.drt.time.UtcDate

import scala.concurrent.{ExecutionContext, Future}

object FlightsLiveView {
  private val log = LoggerFactory.getLogger(getClass)

  def updateAndRemove(flightDao: FlightDao,
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

  def updateCapacityForDate(airportConfig: AirportConfig, aggregatedDb: AggregatedDbTables, feedService: FeedService)
                           (implicit ec: ExecutionContext): UtcDate => Future[Done] = {
    val flightsForDate: UtcDate => Future[Seq[ApiFlightWithSplits]] = {
      val getFlights = FlightDao().getForUtcDate(airportConfig.portCode)
      (d: UtcDate) => aggregatedDb.run(getFlights(d))
    }

    val uniqueFlightsForDate = PassengersLiveView.uniqueFlightsForDate(
      flights = flightsForDate,
      baseArrivals = feedService.aclArrivalsForDate,
      paxFeedSourceOrder = feedService.paxFeedSourceOrder,
    )

    val getCapacities = PassengersLiveView.capacityForDate(uniqueFlightsForDate)
    val persistCapacity = PassengersLiveView.persistCapacityForDate(aggregatedDb, airportConfig.portCode)

    PassengersLiveView.updateAndPersistCapacityForDate(getCapacities, persistCapacity)
  }
}
