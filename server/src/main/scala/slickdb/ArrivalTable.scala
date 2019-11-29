package slickdb

import java.sql.Timestamp

import drt.shared
import drt.shared.{Arrival, PortCode}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.Terminal
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps


case class AggregatedArrivals(arrivals: Seq[AggregatedArrival])

case class AggregatedArrival(code: String, scheduled: MillisSinceEpoch, origin: String, destination: String, terminalName: String)

object AggregatedArrival {
  def apply(arrival: Arrival, destination: String): AggregatedArrival = AggregatedArrival(
    arrival.IATA,
    arrival.Scheduled,
    arrival.Origin.toString,
    destination,
    terminalName = arrival.Terminal.toString
  )
}

case class ArrivalTable(portCode: PortCode, tables: Tables) extends ArrivalTableLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import tables.profile.api._
  import tables.{Arrival, ArrivalRow}

  val db: tables.profile.backend.DatabaseDef = Database.forConfig("aggregated-db")
  val arrivalsTableQuery = TableQuery[Arrival]

  def selectAll: AggregatedArrivals = {
    val eventualArrivals = db.run(arrivalsTableQuery.result).map(arrivalRows =>
      arrivalRows.map(ar =>
        AggregatedArrival(ar.code, ar.scheduled.getTime, ar.origin, ar.destination, ar.terminal)))
    val arrivals = Await.result(eventualArrivals, 5 seconds)
    AggregatedArrivals(arrivals)
  }

  def removeArrival(number: Int, terminal: Terminal, scheduledTs: Timestamp): Future[Int] = {
    val idx = matchIndex(number, terminal, scheduledTs)
    log.info(s"removing: $number / ${terminal.toString} / $scheduledTs")
    db.run(arrivalsTableQuery.filter(idx).delete) recover {
      case throwable =>
        log.error(s"delete failed", throwable)
        0
    }
  }

  def insertOrUpdateArrival(f: shared.Arrival): Future[Int] = {
    db.run(arrivalsTableQuery.insertOrUpdate(arrivalRow(f))) recover {
      case throwable =>
        log.error(s"insertOrUpdate failed", throwable)
        0
    }
  }

  def matchIndex(number: Int, terminal: Terminal, scheduledTs: Timestamp): tables.Arrival => Rep[Boolean] = (arrival: Arrival) =>
    arrival.number === number &&
      arrival.terminal === terminal.toString &&
      arrival.scheduled === scheduledTs &&
      arrival.destination === portCode.toString

  def arrivalRow(f: shared.Arrival): tables.ArrivalRow = {
    val sch = new Timestamp(f.Scheduled)
    val est = f.Estimated.map(new Timestamp(_))
    val act = f.Actual.map(new Timestamp(_))
    val estChox = f.EstimatedChox.map(new Timestamp(_))
    val actChox = f.ActualChox.map(new Timestamp(_))
    val pcp = new Timestamp(f.PcpTime.getOrElse(f.Scheduled))
    val pcpPax = f.ActPax.map(ap => ap - f.TranPax.getOrElse(0))

    ArrivalRow(f.IATA, f.flightNumber, portCode.iata, f.Origin.toString, f.Terminal.toString, f.Gate, f.Stand, f.Status.description, sch, est, act, estChox, actChox, pcp, f.ActPax, pcpPax)
  }
}
