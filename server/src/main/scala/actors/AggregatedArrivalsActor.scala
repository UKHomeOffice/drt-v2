package actors

import akka.Done
import akka.actor.Actor
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import drt.shared._
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}
import services.StreamSupervision
import slickdb.ArrivalTableLike

import java.sql.Timestamp
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}


class AggregatedArrivalsActor(arrivalTable: ArrivalTableLike) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)
  implicit val executionContext: ExecutionContextExecutor = ExecutionContext.global
  implicit val materializer: ActorMaterializer = ActorMaterializer.create(context)

  override def receive: Receive = {
    case ArrivalsDiff(toUpdate, toRemove) =>
      handleUpdates(toUpdate.values)
        .andThen { case _ =>
          handleRemovals(toRemove)
        }

    case other =>
      log.error(s"Received unexpected message ${other.getClass}")
  }

  def handleUpdates(toUpdate: Iterable[Arrival]): Future[Done] =
    Source(toUpdate.toList)
      .mapAsync(2) { arrival =>
        arrivalTable.insertOrUpdateArrival(arrival)
      }
      .withAttributes(StreamSupervision.resumeStrategyWithLog(getClass.getName))
      .runWith(Sink.ignore)

  def handleRemovals(toRemove: Iterable[Arrival]): Future[Done] =
    Source(toRemove.toList)
      .mapAsync(2)(a =>
        arrivalTable.removeArrival(a.VoyageNumber.numeric, a.Terminal, new Timestamp(a.Scheduled)))
      .withAttributes(StreamSupervision.resumeStrategyWithLog(getClass.getName))
      .runWith(Sink.ignore)
}
