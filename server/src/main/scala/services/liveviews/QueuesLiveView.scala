package services.liveviews

import drt.shared.CrunchApi.CrunchMinutes
import slickdb.AggregatedDbTables
import uk.gov.homeoffice.drt.db.dao.QueueSlotDao
import uk.gov.homeoffice.drt.model.CrunchMinute
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

import scala.concurrent.Future

object QueuesLiveView {
  def updateFlightsLiveView(queueSlotDao: QueueSlotDao,
                            aggregatedDb: AggregatedDbTables,
                            portCode: PortCode,
                           ): (UtcDate, Iterable[CrunchMinute]) => Future[Int] = {
    val slotSizeMinutes = 15
    val insertOrUpdate = queueSlotDao.insertOrUpdateMulti(portCode, slotSizeMinutes)

    (date, crunchMinutes) => {
      val summaries = CrunchMinutes.groupByMinutes(slotSizeMinutes, crunchMinutes.toSeq, date)(d => SDate(d).millisSinceEpoch)
      aggregatedDb.run(insertOrUpdate(summaries))
    }
  }
}
