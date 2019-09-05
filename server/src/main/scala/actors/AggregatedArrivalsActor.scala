package actors

import java.sql.Timestamp

import akka.actor.Actor
import drt.shared.{ApiFlightWithSplits, PortStateDiff, RemoveFlight, UniqueArrival}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import slickdb.ArrivalTableLike

import scala.concurrent.Await
import scala.concurrent.duration._


class AggregatedArrivalsActor(portCode: String, arrivalTable: ArrivalTableLike) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def receive: Receive = {
    case PortStateDiff(flightRemovals, flightUpdates, _, _) =>
      handleUpdates(flightUpdates.values.toSet)

      handleRemovals(flightRemovals.toSet)
  }

  def handleRemovals(flightRemovals: Set[RemoveFlight]): Unit = {
    flightRemovals.foreach {
      case RemoveFlight(UniqueArrival(number, terminalName, scheduled, _)) =>
        val scheduledIso = SDate(scheduled).toISOString()
        val scheduledTs = new Timestamp(scheduled)
        log.info(s"Removing $portCode / $terminalName / $number / $scheduledIso")
        Await.result(arrivalTable.removeArrival(number, terminalName, scheduledTs), 1 seconds)
    }
  }

  def handleUpdates(flightUpdates: Set[ApiFlightWithSplits]): Unit = {
    flightUpdates.foreach {
      case ApiFlightWithSplits(f, _, _) => Await.result(arrivalTable.insertOrUpdateArrival(f), 1 seconds)
    }
  }
}
