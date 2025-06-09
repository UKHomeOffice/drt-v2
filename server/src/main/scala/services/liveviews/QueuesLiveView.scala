package services.liveviews

import drt.shared.CrunchApi.CrunchMinutes
import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.db.AggregatedDbTables
import uk.gov.homeoffice.drt.db.dao.QueueSlotDao
import uk.gov.homeoffice.drt.models.CrunchMinute
import uk.gov.homeoffice.drt.ports.{PortCode, Terminals}
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
      val slotStarts = summaries.map(_.minute)
      val firstSlot = slotStarts.min
      val lastSlot = slotStarts.max

      val terminals = crunchMinutes.groupBy(_.terminal).keys

      aggregatedDb
        .run(queueSlotDao.removeTerminalSlots(portCode, terminals, slotSizeMinutes, firstSlot, lastSlot))
        .flatMap { removed =>
          log.info(s"Removed $removed slots for $portCode $terminals on $date")
          aggregatedDb
            .run(insertOrUpdate(summaries))
            .recover { case e: Throwable =>
              log.error(s"Error updating QueuesLiveView for $portCode $terminals on $date: ${e.getMessage}")
              0
            }
        }
        .map { rowsUpdated =>
          log.info(s"Updated QueuesLiveView with $rowsUpdated rows for $portCode $terminals on $date")
          rowsUpdated
        }
        .recover { case e: Throwable =>
          log.error(s"Error removing queue slots for $portCode on $date: ${e.getMessage}")
          0
        }
    }
  }
}
