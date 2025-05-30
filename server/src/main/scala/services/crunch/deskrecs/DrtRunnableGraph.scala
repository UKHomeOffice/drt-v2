package services.crunch.deskrecs

import actors.persistent.SortedActorRefSource
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.{Materializer, UniqueKillSwitch}
import org.apache.pekko.{Done, NotUsed}
import org.slf4j.LoggerFactory
import slick.lifted.Rep
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.db.AggregatedDbTables
import uk.gov.homeoffice.drt.db.dao.StatusDailyDao
import uk.gov.homeoffice.drt.db.tables.{StatusDaily, StatusDailyTable}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.LocalDate

import java.sql.Timestamp
import scala.collection.SortedSet
import scala.concurrent.{ExecutionContext, Future}

trait DrtRunnableGraph {
  def startQueuedRequestProcessingGraph[A](minutesProducer: Flow[TerminalUpdateRequest, A, NotUsed],
                                           persistentQueueActor: ActorRef,
                                           initialQueue: SortedSet[TerminalUpdateRequest],
                                           sinkActor: ActorRef,
                                           graphName: String,
                                          )
                                          (implicit mat: Materializer): (ActorRef, UniqueKillSwitch) = {
    val graphSource = new SortedActorRefSource(persistentQueueActor, initialQueue, graphName)
    QueuedRequestProcessing.createGraph(graphSource, sinkActor, minutesProducer, graphName).run()
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
