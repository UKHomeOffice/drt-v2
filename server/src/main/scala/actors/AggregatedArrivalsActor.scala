package actors

import java.sql.Timestamp

import akka.actor.Actor
import drt.shared.ApiFlightWithSplits
import drtdb.Tables.{Arrival, ArrivalRow}
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch.CrunchDiff
import slick.jdbc.PostgresProfile.api._

class AggregatedArrivalsActor(portCode: String) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val db = Database.forConfig("queryable-db")
  val arrivalsTableQuery: TableQuery[Arrival] = TableQuery[Arrival]

  override def receive: Receive = {
    case CrunchDiff(_, flightUpdates, _, _) =>
      flightUpdates.map {
        case ApiFlightWithSplits(f, _, _) =>
          val sch = new Timestamp(f.Scheduled)
          val est = f.Estimated.map(new Timestamp(_))
          val act = f.Actual.map(new Timestamp(_))
          val estChox = f.EstimatedChox.map(new Timestamp(_))
          val actChox = f.ActualChox.map(new Timestamp(_))
          val pcp = new Timestamp(f.PcpTime.getOrElse(f.Scheduled))
          val pcpPax = f.ActPax.map(ap => ap - f.TranPax.getOrElse(0))
          val row = ArrivalRow(f.IATA, f.voyageNumberPadded, portCode, f.Origin, f.Terminal, f.Gate, f.Stand, f.Status, sch, est, act, estChox, actChox, pcp, f.ActPax, pcpPax)

          log.info(s"Upserting ${f.IATA}")

          db.run(arrivalsTableQuery.insertOrUpdate(row))
      }
  }
}
