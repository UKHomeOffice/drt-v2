package services.liveviews

import drt.shared.CrunchApi.CrunchMinutes
import org.slf4j.LoggerFactory
import slickdb.AggregatedDbTables
import uk.gov.homeoffice.drt.db.dao.QueueSlotDao
import uk.gov.homeoffice.drt.model.CrunchMinute
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

object QueuesLiveView {
  private val log = LoggerFactory.getLogger(getClass)

  def updateQueuesLiveView(queueSlotDao: QueueSlotDao,
                           aggregatedDb: AggregatedDbTables,
                           portCode: PortCode,
                           )
                          (implicit ec: ExecutionContext): (UtcDate, Iterable[CrunchMinute]) => Future[Int] = {
    val slotSizeMinutes = 15
    val insertOrUpdate = queueSlotDao.insertOrUpdateMulti(portCode, slotSizeMinutes)

    (date, crunchMinutes) => {
      val summaries = CrunchMinutes.groupByMinutes(slotSizeMinutes, crunchMinutes.toSeq, date)(d => SDate(d).millisSinceEpoch)
      aggregatedDb
        .run(insertOrUpdate(summaries))
        .recover { case e: Throwable =>
          log.error(s"Error updating QueuesLiveView: ${e.getMessage}")
          0
        }
    }
  }
}
