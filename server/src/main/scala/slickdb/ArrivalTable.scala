package slickdb

import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.{Arrival => DrtArrival}
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import java.sql.Timestamp
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps


case class AggregatedArrivals(arrivals: Seq[AggregatedArrival])

case class AggregatedArrival(code: String, scheduled: MillisSinceEpoch, origin: String, destination: String, terminalName: String)

object AggregatedArrival {
  def apply(arrival: DrtArrival, destination: String): AggregatedArrival = AggregatedArrival(
    arrival.flightCodeString,
    arrival.Scheduled,
    arrival.Origin.toString,
    destination,
    terminalName = arrival.Terminal.toString
  )
}

case class ArrivalTable(portCode: PortCode, tables: Tables, paxFeedSourceOrder: List[FeedSource]) extends ArrivalTableLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import tables.profile.api._
  import tables.{Arrival, ArrivalRow}

  private val arrivalsTableQuery = TableQuery[Arrival]

  def selectAll: AggregatedArrivals = {
    val eventualArrivals = tables.run(arrivalsTableQuery.result).map(arrivalRows =>
      arrivalRows.map(ar =>
        AggregatedArrival(ar.code, ar.scheduled.getTime, ar.origin, ar.destination, ar.terminal)))
    val arrivals = Await.result(eventualArrivals, 5 seconds)
    AggregatedArrivals(arrivals)
  }

  def removeArrival(number: Int, terminal: Terminal, scheduledTs: Timestamp): Future[Int] = {
    val idx = matchIndex(number, terminal, scheduledTs)
    tables.run(arrivalsTableQuery.filter(idx).delete) recover {
      case throwable =>
        log.error(s"delete failed", throwable)
        0
    }
  }

  def insertOrUpdateArrival(f: DrtArrival): Future[Int] = {
    tables.run(arrivalsTableQuery.insertOrUpdate(arrivalRow(f))) recover {
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

  def arrivalRow(f: uk.gov.homeoffice.drt.arrivals.Arrival): tables.ArrivalRow = {
    val sch = new Timestamp(f.Scheduled)
    val est = f.Estimated.map(new Timestamp(_))
    val act = f.Actual.map(new Timestamp(_))
    val estChox = f.EstimatedChox.map(new Timestamp(_))
    val actChox = f.ActualChox.map(new Timestamp(_))
    val pcp = new Timestamp(f.PcpTime.getOrElse(f.Scheduled))
    val pcpPax = f.bestPaxEstimate(paxFeedSourceOrder).passengers.getPcpPax
    val scheduledDeparture = f.ScheduledDeparture.map(new Timestamp(_))

    ArrivalRow(f.flightCodeString,
      f.VoyageNumber.numeric,
      portCode.iata,
      f.Origin.toString,
      f.Terminal.toString,
      f.Gate,
      f.Stand,
      f.Status.description,
      sch,
      est,
      act,
      estChox,
      actChox,
      pcp,
      f.bestPaxEstimate(paxFeedSourceOrder).passengers.actual,
      pcpPax,
      scheduledDeparture)
  }
}
