package services.crunch.deskrecs

import actors.persistent.SortedActorRefSource
import akka.{Done, NotUsed}
import akka.actor.ActorRef
import akka.stream.{Materializer, UniqueKillSwitch}
import akka.stream.scaladsl.Flow
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.LoggerFactory
import slick.lifted.Rep
import slickdb.AggregatedDbTables
import uk.gov.homeoffice.drt.actor.commands.{ProcessingRequest, TerminalUpdateRequest}
import uk.gov.homeoffice.drt.db.{StatusDaily, StatusDailyTable}
import uk.gov.homeoffice.drt.db.dao.StatusDailyDao
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import java.sql.Timestamp
import scala.collection.SortedSet
import scala.concurrent.{ExecutionContext, Future}

trait DrtRunnableGraph {
  def startQueuedRequestProcessingGraph[A](minutesProducer: Flow[ProcessingRequest, A, NotUsed],
                                           persistentQueueActor: ActorRef,
                                           initialQueue: SortedSet[ProcessingRequest],
                                           sinkActor: ActorRef,
                                           graphName: String,
                                           processingRequest: MillisSinceEpoch => ProcessingRequest,
                                          )
                                          (implicit mat: Materializer): (ActorRef, UniqueKillSwitch) = {
    val graphSource = new SortedActorRefSource(persistentQueueActor, processingRequest, initialQueue, graphName)
    QueuedRequestProcessing.createGraph(graphSource, sinkActor, minutesProducer, graphName).run()
  }

  def setUpdatedAtForTerminals(terminals: Iterable[Terminal],
                               setUpdatedAtForDay: (Terminal, LocalDate, MillisSinceEpoch) => Future[Done],
                               pr: ProcessingRequest): Unit =
    pr match {
      case TerminalUpdateRequest(terminal, localDate, _, _) =>
        setUpdatedAtForDay(terminal, localDate, SDate.now().millisSinceEpoch)
      case other =>
        val localDate = LocalDate(other.date.year, other.date.month, other.date.day)
        terminals.foreach { terminal =>
          setUpdatedAtForDay(terminal, localDate, SDate.now().millisSinceEpoch)
        }
    }
}

object DrtRunnableGraph extends DrtRunnableGraph {
  private val log = LoggerFactory.getLogger(getClass)

  def setUpdatedAt(portCode: PortCode,
                   aggregatedDb: AggregatedDbTables,
                   columnToUpdate: StatusDailyTable => Rep[Option[Timestamp]],
                   setUpdated: (StatusDaily, Long) => StatusDaily,
                  )
                  (implicit ec: ExecutionContext): (Terminal, LocalDate, Long) => Future[Done] = {
    (terminal, date, updatedAt) => {
      val setter = StatusDailyDao.setUpdatedAt(portCode)(columnToUpdate)
      val inserter = StatusDailyDao.insertOrUpdate(portCode)
      aggregatedDb
        .run(setter(terminal, date, updatedAt))
        .flatMap { count =>
          if (count > 0) Future.successful(Done)
          else {
            val newStatus = StatusDaily(portCode, terminal, date, None, None, None, None)
            val toInsert = setUpdated(newStatus, updatedAt)
            aggregatedDb.run(inserter(toInsert)).map(_ => Done)
          }
        }
        .recover {
          case t =>
            log.error(s"Failed to update status for $terminal on $date", t)
            Done
        }
    }
  }
}
