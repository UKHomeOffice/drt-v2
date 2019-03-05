package services

import java.sql.Timestamp

import drt.shared.SDateLike
import drt.shared.SplitRatiosNs.SplitSources
import manifests.passengers.{BestAvailableManifest, ManifestPassengerProfile}
import org.slf4j.{Logger, LoggerFactory}
import slick.jdbc.SQLActionBuilder
import slick.sql.SqlStreamingAction
import slickdb.VoyageManifestPassengerInfoTable

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait ManifestLookupLike {
  def tryBestAvailableManifest(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike): Future[Try[BestAvailableManifest]]
}

case class ManifestLookup(paxInfoTable: VoyageManifestPassengerInfoTable) extends ManifestLookupLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import paxInfoTable.tables.profile.api._

  def manifestForScheduled(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike, manifestScheduled: java.sql.Timestamp): Future[Try[BestAvailableManifest]] = paxInfoTable.db
    .run(paxForArrivalQuery(arrivalPort, departurePort, voyageNumber, manifestScheduled).as[(String, String, String, String, String, Boolean)])
    .map { rows =>
      Success(BestAvailableManifest(SplitSources.Historical, arrivalPort, departurePort, voyageNumber, "xx", scheduled, passengerProfiles(rows).toList))
    }.recover { case t =>
      log.error(s"Didn't get pax for $arrivalPort -> $departurePort: $voyageNumber @ ${scheduled.toISOString()}", t)
      Failure(t)
    }

  def manifestSearch(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike, queries: List[(String, QueryFunction)]): Future[Try[BestAvailableManifest]] = queries match {
    case Nil => Future(Failure(new Exception(s"No manifests found for $arrivalPort -> $departurePort: $voyageNumber @ ${scheduled.toISOString()}")))
    case (_, nextQuery) :: tail =>
      paxInfoTable.db
        .run(nextQuery(arrivalPort, departurePort, voyageNumber, scheduled))
        .map(_.headOption match {
          case Some(ts) =>
            manifestForScheduled(arrivalPort, departurePort, voyageNumber, scheduled: SDateLike, ts)
          case None =>
            manifestSearch(arrivalPort, departurePort, voyageNumber, scheduled, tail)
        })
        .flatMap(identity)
  }

  def tryBestAvailableManifest(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike): Future[Try[BestAvailableManifest]] =
    manifestSearch(arrivalPort, departurePort, voyageNumber, scheduled, queryHierarchy)

  type QueryFunction = (String, String, String, SDateLike) => SqlStreamingAction[Vector[Timestamp], Timestamp, paxInfoTable.tables.profile.api.Effect]
  private val queryHierarchy: List[(String, QueryFunction)] = List(
    ("Most recent same flight on same DOW", mostRecentFlightSameDayOfWeekQuery),
    ("Most recent same flight", mostRecentFlightQuery),
    ("Most recent same route on same DOW", mostRecentRouteSameDayOfWeekQuery),
    ("Most recent same route", mostRecentRouteQuery)
  )

  def dowToPostgres = Map(
    1 -> 1,
    2 -> 2,
    3 -> 3,
    4 -> 4,
    5 -> 5,
    6 -> 6,
    7 -> 0
  )

  def mostRecentFlightSameDayOfWeekQuery(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike): SqlStreamingAction[Vector[Timestamp], Timestamp, Effect] =
    sql"""select scheduled_date
          from voyage_manifest_passenger_info
          where event_code ='DC'
            and arrival_port_code=$arrivalPort
            and departure_port_code=$departurePort
            and voyager_number=$voyageNumber
            and day_of_week = ${dowToPostgres(scheduled.getDayOfWeek())}
          order by scheduled_date DESC
          LIMIT 1""".as[java.sql.Timestamp]

  def mostRecentFlightQuery(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike): SqlStreamingAction[Vector[Timestamp], Timestamp, Effect] =
    sql"""select scheduled_date
          from voyage_manifest_passenger_info
          where event_code ='DC'
            and arrival_port_code=$arrivalPort
            and departure_port_code=$departurePort
            and voyager_number=$voyageNumber
          order by scheduled_date DESC
          LIMIT 1""".as[java.sql.Timestamp]

  def mostRecentRouteSameDayOfWeekQuery(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike): SqlStreamingAction[Vector[Timestamp], Timestamp, Effect] =
    sql"""select scheduled_date
          from voyage_manifest_passenger_info
          where event_code ='DC'
            and arrival_port_code=$arrivalPort
            and departure_port_code=$departurePort
            and day_of_week = ${dowToPostgres(scheduled.getDayOfWeek())}
          order by scheduled_date DESC
          LIMIT 1""".as[java.sql.Timestamp]

  def mostRecentRouteQuery(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike): SqlStreamingAction[Vector[Timestamp], Timestamp, Effect] =
    sql"""select scheduled_date
          from voyage_manifest_passenger_info
          where event_code ='DC'
            and arrival_port_code=$arrivalPort
            and departure_port_code=$departurePort
          order by scheduled_date DESC
          LIMIT 1""".as[java.sql.Timestamp]

  def paxForArrivalQuery(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: Timestamp): SQLActionBuilder =
    sql"""select
            nationality_country_code,
            document_type,
            age,
            in_transit_flag,
            disembarkation_port_country_code,
            in_transit
          from voyage_manifest_passenger_info
          where event_code ='DC'
            and arrival_port_code=$arrivalPort
            and departure_port_code=$departurePort
            and voyager_number=$voyageNumber
            and scheduled_date=$scheduled"""

  def passengerProfiles(rows: Vector[(String, String, String, String, String, Boolean)]): Vector[ManifestPassengerProfile] = {
    val paxProfiles = rows.map {
      case (nat, doc, age, transitFlag, endCountry, inTransit) =>
        val transit = (transitFlag, endCountry, inTransit) match {
          case (t, _, _) if t == "Y" => true
          case (_, c, _) if c != "GBR" => true
          case (_, _, t) if t => true
          case _ => false
        }
        ManifestPassengerProfile(nat, Option(doc), Try(age.toInt).toOption, Option(transit))
    }
    paxProfiles
  }
}
