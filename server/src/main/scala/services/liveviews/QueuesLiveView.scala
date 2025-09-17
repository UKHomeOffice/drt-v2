package services.liveviews

import drt.shared.CrunchApi.CrunchMinutes
import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.db.AggregatedDbTables
import uk.gov.homeoffice.drt.db.dao.QueueSlotDao
import uk.gov.homeoffice.drt.models.CrunchMinute
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
    val insertOrUpdate = queueSlotDao.updateAndRemoveSlots(portCode, slotSizeMinutes)

    (date, minutes) => {
      val slotsToInsert = CrunchMinutes.groupByMinutes(slotSizeMinutes, minutes.toSeq, date)(d => SDate(d).millisSinceEpoch)

      aggregatedDb
        .run(insertOrUpdate(slotsToInsert, Seq.empty))
        .recover { case e: Throwable =>
          log.error(s"Error updating QueuesLiveView for $portCode on $date: ${e.getMessage}")
          0
        }
        .map { rowsUpdated =>
          log.info(s"Updated QueuesLiveView with $rowsUpdated rows for $portCode on $date")
          rowsUpdated
        }
        .recover { case e: Throwable =>
          log.error(s"Error removing queue slots for $portCode on $date: ${e.getMessage}")
          0
        }
    }
  }
}
