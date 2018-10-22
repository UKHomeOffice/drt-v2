package actors

import java.sql.Timestamp

import akka.actor.Actor
import drt.shared
import drt.shared.{ApiFlightWithSplits, UniqueArrival}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch.{PortStateDiff, RemoveFlight}
import slickdb.Tables

import scala.concurrent.ExecutionContext.Implicits.global

class AggregatedArrivalsActor(portCode: String, tables: Tables) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import tables.{Arrival, ArrivalRow}
  import tables.profile.api._

  private val db: tables.profile.backend.DatabaseDef = Database.forConfig("aggregated-db")
  private val arrivalsTableQuery = TableQuery[Arrival]

  override def receive: Receive = {
    case PortStateDiff(flightRemovals, flightUpdates, _, _) =>
      flightUpdates.map {
        case ApiFlightWithSplits(f, _, _) =>
          log.info(s"Upserting ${f.IATA}")
          db.run(arrivalsTableQuery.insertOrUpdate(arrivalRow(f))).recover {
            case throwable => log.error(s"insertOrUpdate failed", throwable)
          }
      }

      flightRemovals.map {
        case RemoveFlight(UniqueArrival(number, terminalName, scheduled)) =>
          val scheduledIso = SDate(scheduled).toISOString()
          val scheduledTs = new Timestamp(scheduled)
          log.info(s"Removing $portCode / $terminalName / $number / $scheduledIso")
          db.run(arrivalsTableQuery.filter(matchIndex(number, terminalName, scheduledTs)).delete).recover {
            case throwable => log.error(s"delete failed", throwable)
          }
      }
  }

  private def matchIndex(number: Int, terminalName: String, scheduledTs: Timestamp) = (arrival: Arrival) =>
    arrival.number === number &&
      arrival.terminal === terminalName &&
      arrival.scheduled === scheduledTs &&
      arrival.destination === portCode

  private def arrivalRow(f: shared.Arrival) = {
    val sch = new Timestamp(f.Scheduled)
    val est = f.Estimated.map(new Timestamp(_))
    val act = f.Actual.map(new Timestamp(_))
    val estChox = f.EstimatedChox.map(new Timestamp(_))
    val actChox = f.ActualChox.map(new Timestamp(_))
    val pcp = new Timestamp(f.PcpTime.getOrElse(f.Scheduled))
    val pcpPax = f.ActPax.map(ap => ap - f.TranPax.getOrElse(0))

    ArrivalRow(f.IATA, f.flightNumber, portCode, f.Origin, f.Terminal, f.Gate, f.Stand, f.Status, sch, est, act, estChox, actChox, pcp, f.ActPax, pcpPax)
  }
}
