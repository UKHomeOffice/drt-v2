package actors

import java.sql.Timestamp

import akka.actor.Actor
import drt.shared.{ApiFlightWithSplits, UniqueArrival}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch.{PortStateDiff, RemoveFlight}
import slickdb.ArrivalTableLike

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps


class AggregatedArrivalsActor(portCode: String, arrivalTable: ArrivalTableLike) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def receive: Receive = {
    case PortStateDiff(flightRemovals, flightUpdates, _, _) =>
      handleUpdates(flightUpdates)

      handleRemovals(flightRemovals)
  }

  def handleRemovals(flightRemovals: Set[RemoveFlight]): Unit = {
    flightRemovals.foreach {
      case RemoveFlight(UniqueArrival(number, terminalName, scheduled)) =>
        val scheduledIso = SDate(scheduled).toISOString()
        val scheduledTs = new Timestamp(scheduled)
        log.info(s"Removing $portCode / $terminalName / $number / $scheduledIso")
        Await.result(arrivalTable.removeArrival(number, terminalName, scheduledTs), 10 seconds)
    }
  }

  def handleUpdates(flightUpdates: Set[ApiFlightWithSplits]): Unit = {
    flightUpdates.foreach {
      case ApiFlightWithSplits(f, _, _) => Await.result(arrivalTable.insertOrUpdateArrival(f), 10 seconds)
    }
  }
}

