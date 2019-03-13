package manifests

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
  def maybeBestAvailableManifest(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike): Future[(UniqueArrivalKey, Option[BestAvailableManifest])]
}

case class UniqueArrivalKey(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike) {
  override def toString: String = s"$arrivalPort -> $departurePort: $voyageNumber @ ${scheduled.toISOString()}"
}

case class ManifestLookup(paxInfoTable: VoyageManifestPassengerInfoTable) extends ManifestLookupLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import paxInfoTable.tables.profile.api._

  def manifestTriesForScheduled(arrivalToLookUp: UniqueArrivalKey, flightKeys: Vector[(String, String, String, Timestamp)]): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] = {
    Future
      .sequence(
        paxForArrivalQuery(flightKeys).map { builder =>
          paxInfoTable.db
            .run(builder.as[(String, String, String, String, String, Boolean)])
            .map { rows =>
              Success(passengerProfiles(rows).toList)
            }
            .recover { case t =>
              log.error(s"Didn't get pax for $arrivalToLookUp", t)
              Failure(t)
            }
        }
      )
      .map { tries =>
        tries.collect {
          case Failure(t) => log.warn(s"Failed to get manifests", t)
        }

        val profiles = tries.collect { case Success(paxProfiles) => paxProfiles }.flatten

        val maybeManifests = if (profiles.nonEmpty)
          Option(BestAvailableManifest(SplitSources.Historical, arrivalToLookUp, profiles.toList))
        else
          None

        (arrivalToLookUp, maybeManifests)
      }
  }

  def manifestSearch(uniqueArrivalKey: UniqueArrivalKey, queries: List[(String, QueryFunction)]): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] = queries match {
    case Nil =>
      log.warn(s"No manifests found for $uniqueArrivalKey")
      Future((uniqueArrivalKey, None))
    case (label, nextQuery) :: tail =>
      paxInfoTable.db
        .run(nextQuery(uniqueArrivalKey))
        .map {
          case flightsFound if flightsFound.nonEmpty =>
            manifestTriesForScheduled(uniqueArrivalKey, flightsFound)
          case _ =>
            manifestSearch(uniqueArrivalKey, tail)
        }
        .flatMap(identity)
  }

  def maybeBestAvailableManifest(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] =
    manifestSearch(UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), queryHierarchy)

  type QueryFunction = UniqueArrivalKey => SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), paxInfoTable.tables.profile.api.Effect]

  private val queryHierarchy: List[(String, QueryFunction)] = List(
    ("sameFlightAndDay3WeekWindowPreviousYearQuery", sameFlightAndDay3WeekWindowPreviousYearQuery),
    ("sameFlight3WeekWindowPreviousYearQuery", sameFlight3WeekWindowPreviousYearQuery),
    ("sameRouteAndDay3WeekWindowPreviousYearQuery", sameRouteAndDay3WeekWindowPreviousYearQuery)
  )

  def sameFlightAndDay3WeekWindowPreviousYearQuery(uniqueArrivalKey: UniqueArrivalKey): SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), Effect] = {
    val earliestWeek = uniqueArrivalKey.scheduled.addMonths(-12).addDays(-7)
    val latestWeek = uniqueArrivalKey.scheduled.addMonths(-12).addDays(7)
    val scheduledTs = uniqueArrivalKey.scheduled.toISODateOnly
    val earliestTs = earliestWeek.toISODateOnly
    val latestTs = latestWeek.toISODateOnly

    sql"""SELECT
            arrival_port_code,
            departure_port_code,
            voyager_number,
            scheduled_date
          FROM
            voyage_manifest_passenger_info
          WHERE
            event_code ='DC'
            and arrival_port_code=${uniqueArrivalKey.arrivalPort}
            and departure_port_code=${uniqueArrivalKey.departurePort}
            and voyager_number=${uniqueArrivalKey.voyageNumber}
            and day_of_week = EXTRACT(DOW FROM TIMESTAMP '#$scheduledTs')
            and week_of_year >= EXTRACT(WEEK FROM TIMESTAMP '#$earliestTs')
            and week_of_year <= EXTRACT(WEEK FROM TIMESTAMP '#$latestTs')
          GROUP BY
            arrival_port_code,
            departure_port_code,
            voyager_number,
            scheduled_date
          """.as[(String, String, String, Timestamp)]
  }


  def sameFlight3WeekWindowPreviousYearQuery(uniqueArrivalKey: UniqueArrivalKey): SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), Effect] = {
    val earliestWeek = uniqueArrivalKey.scheduled.addMonths(-12).addDays(-7)
    val latestWeek = uniqueArrivalKey.scheduled.addMonths(-12).addDays(7)
    val earliestTs = earliestWeek.toISODateOnly
    val latestTs = latestWeek.toISODateOnly
    sql"""SELECT
            arrival_port_code,
            departure_port_code,
            voyager_number,
            scheduled_date
          FROM
            voyage_manifest_passenger_info
          WHERE
            event_code ='DC'
            and arrival_port_code=${uniqueArrivalKey.arrivalPort}
            and departure_port_code=${uniqueArrivalKey.departurePort}
            and voyager_number=${uniqueArrivalKey.voyageNumber}
            and week_of_year >= EXTRACT(WEEK FROM TIMESTAMP '#$earliestTs')
            and week_of_year <= EXTRACT(WEEK FROM TIMESTAMP '#$latestTs')
          GROUP BY
            arrival_port_code,
            departure_port_code,
            voyager_number,
            scheduled_date
          """.as[(String, String, String, Timestamp)]
  }

  def sameRouteAndDay3WeekWindowPreviousYearQuery(uniqueArrivalKey: UniqueArrivalKey): SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), Effect] = {
    val earliestWeek = uniqueArrivalKey.scheduled.addMonths(-12).addDays(-7)
    val latestWeek = uniqueArrivalKey.scheduled.addMonths(-12).addDays(7)
    val scheduledTs = uniqueArrivalKey.scheduled.toISODateOnly
    val earliestTs = earliestWeek.toISODateOnly
    val latestTs = latestWeek.toISODateOnly
    sql"""SELECT
            arrival_port_code,
            departure_port_code,
            voyager_number,
            scheduled_date
          FROM
            voyage_manifest_passenger_info
          WHERE
            event_code ='DC'
            and arrival_port_code=${uniqueArrivalKey.arrivalPort}
            and departure_port_code=${uniqueArrivalKey.departurePort}
            and day_of_week = EXTRACT(DOW FROM TIMESTAMP '#$scheduledTs')
            and week_of_year >= EXTRACT(WEEK FROM TIMESTAMP '#$earliestTs')
            and week_of_year <= EXTRACT(WEEK FROM TIMESTAMP '#$latestTs')
          GROUP BY
            arrival_port_code,
            departure_port_code,
            voyager_number,
            scheduled_date
          LIMIT 3
          """.as[(String, String, String, Timestamp)]
  }

  def paxForArrivalQuery(flightKeys: Vector[(String, String, String, Timestamp)]): Vector[SQLActionBuilder] =
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
