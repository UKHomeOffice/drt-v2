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
  def maybeBestAvailableManifest(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike): Future[Option[BestAvailableManifest]]
}

case class ManifestLookup(paxInfoTable: VoyageManifestPassengerInfoTable) extends ManifestLookupLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import paxInfoTable.tables.profile.api._

  def manifestTriesForScheduled(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike, flightKeys: Vector[(String, String, String, Timestamp)]): Future[Option[BestAvailableManifest]] = {
    Future
      .sequence(
        paxForArrivalQuery(arrivalPort, departurePort, voyageNumber, flightKeys).map { builder =>
          paxInfoTable.db
            .run(builder.as[(String, String, String, String, String, Boolean)])
            .map { rows =>
              Success(passengerProfiles(rows).toList)
            }
            .recover { case t =>
              log.error(s"Didn't get pax for $arrivalPort -> $departurePort: $voyageNumber @ ${scheduled.toISOString()}", t)
              Failure(t)
            }
        }
      )
      .map { tries =>
        tries.collect {
          case Failure(t) => log.warn(s"Failed to get manifests", t)
        }

        val profiles = tries.collect { case Success(flightProfiles) => flightProfiles }.flatten

        if (profiles.nonEmpty)
          Option(BestAvailableManifest(SplitSources.Historical, arrivalPort, departurePort, voyageNumber, "xx", scheduled, profiles.toList))
        else
          None
      }
  }

  def manifestSearch(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike, queries: List[(String, QueryFunction)]): Future[Option[BestAvailableManifest]] = queries match {
    case Nil =>
      log.warn(s"No manifests found for $arrivalPort -> $departurePort: $voyageNumber @ ${scheduled.toISOString()}")
      Future(None)
    case (_, nextQuery) :: tail =>
      paxInfoTable.db
        .run(nextQuery(arrivalPort, departurePort, voyageNumber, scheduled))
        .map {
          case timestamps if timestamps.nonEmpty =>
            manifestTriesForScheduled(arrivalPort, departurePort, voyageNumber, scheduled, timestamps)
          case _ =>
            manifestSearch(arrivalPort, departurePort, voyageNumber, scheduled, tail)
        }
        .flatMap(identity)
  }

  def maybeBestAvailableManifest(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike): Future[Option[BestAvailableManifest]] =
    manifestSearch(arrivalPort, departurePort, voyageNumber, scheduled, queryHierarchy)

  type QueryFunction = (String, String, String, SDateLike) => SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), paxInfoTable.tables.profile.api.Effect]

  private val queryHierarchy: List[(String, QueryFunction)] = List(
    ("sameFlightAndDay3WeekWindowPreviousYearQuery", sameFlightAndDay3WeekWindowPreviousYearQuery),
    ("sameFlight3WeekWindowPreviousYearQuery", sameFlight3WeekWindowPreviousYearQuery),
    ("sameRouteAndDay3WeekWindowPreviousYearQuery", sameRouteAndDay3WeekWindowPreviousYearQuery)
  )
  //  private val queryHierarchy: List[(String, QueryFunction)] = List(
  //    ("Most recent same flight on same DOW", mostRecentFlightSameDayOfWeekQuery),
  //    ("Most recent same flight", mostRecentFlightQuery),
  //    ("Most recent same route on same DOW", mostRecentRouteSameDayOfWeekQuery),
  //    ("Most recent same route", mostRecentRouteQuery)
  //  )

  def sameFlightAndDay3WeekWindowPreviousYearQuery(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike): SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), Effect] = {
    val earliestWeek = scheduled.addMonths(-12).addDays(-7)
    val latestWeek = scheduled.addMonths(-12).addDays(7)
    sql"""SELECT
            arrival_port_code,
            departure_port_code,
            voyager_number,
            scheduled_date
          FROM
            voyage_manifest_passenger_info
          WHERE
            event_code ='DC'
            and arrival_port_code=$arrivalPort
            and departure_port_code=$departurePort
            and voyager_number=$voyageNumber
            and day_of_week = EXTRACT(DOW FROM TIMESTAMP ${new Timestamp(scheduled.millisSinceEpoch)})
            and week_of_year >= EXTRACT(WEEK FROM TIMESTAMP ${new Timestamp(earliestWeek.millisSinceEpoch)})
            and week_of_year <= EXTRACT(WEEK FROM TIMESTAMP ${new Timestamp(latestWeek.millisSinceEpoch)})
          GROUP BY
            arrival_port_code,
            departure_port_code,
            voyager_number,
            scheduled_date
          """.as[(String, String, String, Timestamp)]
  }


  def sameFlight3WeekWindowPreviousYearQuery(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike): SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), Effect] = {
    val earliestWeek = scheduled.addMonths(-12).addDays(-7)
    val latestWeek = scheduled.addMonths(-12).addDays(7)
    sql"""SELECT
            arrival_port_code,
            departure_port_code,
            voyager_number,
            scheduled_date
          FROM
            voyage_manifest_passenger_info
          WHERE
            event_code ='DC'
            and arrival_port_code=$arrivalPort
            and departure_port_code=$departurePort
            and voyager_number=$voyageNumber
            and week_of_year >= EXTRACT(WEEK FROM TIMESTAMP ${new Timestamp(earliestWeek.millisSinceEpoch)})
            and week_of_year <= EXTRACT(WEEK FROM TIMESTAMP ${new Timestamp(latestWeek.millisSinceEpoch)})
          GROUP BY
            arrival_port_code,
            departure_port_code,
            voyager_number,
            scheduled_date
          """.as[(String, String, String, Timestamp)]
  }

  def sameRouteAndDay3WeekWindowPreviousYearQuery(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike): SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), Effect] = {
    val earliestWeek = scheduled.addMonths(-12).addDays(-7)
    val latestWeek = scheduled.addMonths(-12).addDays(7)
    sql"""SELECT
            arrival_port_code,
            departure_port_code,
            voyager_number,
            scheduled_date
          FROM
            voyage_manifest_passenger_info
          WHERE
            event_code ='DC'
            and arrival_port_code=$arrivalPort
            and departure_port_code=$departurePort
            and day_of_week = EXTRACT(DOW FROM TIMESTAMP ${new Timestamp(scheduled.millisSinceEpoch)})
            and week_of_year >= EXTRACT(WEEK FROM TIMESTAMP ${new Timestamp(earliestWeek.millisSinceEpoch)})
            and week_of_year <= EXTRACT(WEEK FROM TIMESTAMP ${new Timestamp(latestWeek.millisSinceEpoch)})
          GROUP BY
            arrival_port_code,
            departure_port_code,
            voyager_number,
            scheduled_date
          LIMIT 3
          """.as[(String, String, String, Timestamp)]
  }

  def mostRecentFlightSameDayOfWeekQuery(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike): SqlStreamingAction[Vector[Timestamp], Timestamp, Effect] =
    sql"""select scheduled_date
          from voyage_manifest_passenger_info
          where event_code ='DC'
            and arrival_port_code=$arrivalPort
            and departure_port_code=$departurePort
            and voyager_number=$voyageNumber
            and day_of_week = EXTRACT(DOW FROM TIMESTAMP ${new Timestamp(scheduled.millisSinceEpoch)})
          order by scheduled_date DESC
          LIMIT 1""".as[Timestamp]

  def mostRecentFlightQuery(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike): SqlStreamingAction[Vector[Timestamp], Timestamp, Effect] =
    sql"""select scheduled_date
          from voyage_manifest_passenger_info
          where event_code ='DC'
            and arrival_port_code=$arrivalPort
            and departure_port_code=$departurePort
            and voyager_number=$voyageNumber
          order by scheduled_date DESC
          LIMIT 1""".as[Timestamp]

  def mostRecentRouteSameDayOfWeekQuery(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike): SqlStreamingAction[Vector[Timestamp], Timestamp, Effect] =
    sql"""select scheduled_date
          from voyage_manifest_passenger_info
          where event_code ='DC'
            and arrival_port_code=$arrivalPort
            and departure_port_code=$departurePort
            and day_of_week = EXTRACT(DOW FROM TIMESTAMP ${new Timestamp(scheduled.millisSinceEpoch)})
          order by scheduled_date DESC
          LIMIT 1""".as[Timestamp]

  def mostRecentRouteQuery(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike): SqlStreamingAction[Vector[Timestamp], Timestamp, Effect] =
    sql"""select scheduled_date
          from voyage_manifest_passenger_info
          where event_code ='DC'
            and arrival_port_code=$arrivalPort
            and departure_port_code=$departurePort
          order by scheduled_date DESC
          LIMIT 1""".as[Timestamp]

  def paxForArrivalQuery(arrivalPort: String, departurePort: String, voyageNumber: String, flightKeys: Vector[(String, String, String, Timestamp)]): Vector[SQLActionBuilder] =
    flightKeys.map {
      case (destination, origin, voyageNumber, scheduled) =>
        sql"""select
            nationality_country_code,
            document_type,
            age,
            in_transit_flag,
            disembarkation_port_country_code,
            in_transit
          from voyage_manifest_passenger_info
          where
            event_code ='DC'
            and arrival_port_code=$destination
            and departure_port_code=$origin
            and voyager_number=$voyageNumber
            and scheduled_date=$scheduled"""
    }

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
