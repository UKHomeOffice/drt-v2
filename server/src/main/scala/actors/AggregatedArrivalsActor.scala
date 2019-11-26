package actors

import java.sql.Timestamp

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import akka.actor.Actor
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import slickdb.ArrivalTableLike

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}


class AggregatedArrivalsActor(portCode: String, arrivalTable: ArrivalTableLike) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)
  implicit val executionContext: ExecutionContextExecutor = ExecutionContext.global

  override def receive: Receive = {
    case StreamInitialized =>
      log.info("Stream initialized!")
      sender() ! Ack

    case RemoveFlight(UniqueArrival(number, terminal, scheduled)) =>
      handleRemoval(number, terminal, scheduled)

    case arrival: Arrival =>
      handleUpdate(arrival)

    case StreamFailure(t) =>
      log.error("Received stream failure", t)

    case StreamCompleted =>
      log.info("Received shutdown")

    case other =>
      log.error(s"Received unexpected message ${other.getClass}")
  }

  def handleRemoval(number: Int, terminal: Terminal, scheduled: MillisSinceEpoch): Unit = {
    val eventualRemoval = arrivalTable.removeArrival(number, terminal, new Timestamp(scheduled))
    val errorMsg = s"Error on removing arrival ($number/$terminal/$scheduled)"
    ackOnCompletion(eventualRemoval, errorMsg)}

  def handleUpdate(arrival: Arrival): Unit = {
    val eventualUpdate = arrivalTable.insertOrUpdateArrival(arrival)
    val errorMsg = s"Error on updating arrival ($arrival)"
    ackOnCompletion(eventualUpdate, errorMsg)}

  private def ackOnCompletion[X](futureToAck: Future[X], errorMsg: String): Unit = {
    val replyTo = sender()
    futureToAck
      .map { _ => replyTo ! Ack }
      .recover { case t =>
        log.error(errorMsg, t)
        replyTo ! Ack
      }
  }
}
