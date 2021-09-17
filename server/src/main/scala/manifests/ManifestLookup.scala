package manifests

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import manifests.passengers.{BestAvailableManifest, ManifestPassengerProfile}
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import services.StreamSupervision
import slick.jdbc.SQLActionBuilder
import slick.sql.SqlStreamingAction
import slickdb.VoyageManifestPassengerInfoTable
import uk.gov.homeoffice.drt.Nationality

import java.sql.Timestamp
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait ManifestLookupLike {
  def maybeBestAvailableManifest(arrivalPort: PortCode,
                                 departurePort: PortCode,
                                 voyageNumber: VoyageNumber,
                                 scheduled: SDateLike)
                                (implicit mat: Materializer): Future[(UniqueArrivalKey, Option[BestAvailableManifest])]
}

case class UniqueArrivalKey(arrivalPort: PortCode,
                            departurePort: PortCode,
                            voyageNumber: VoyageNumber,
                            scheduled: SDateLike) {
  override def toString: String = s"$arrivalPort -> $departurePort: $voyageNumber @ ${scheduled.toISOString()}"
}

case class ManifestLookup(paxInfoTable: VoyageManifestPassengerInfoTable) extends ManifestLookupLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import paxInfoTable.tables.profile.api._

  def manifestTriesForScheduled(arrivalToLookUp: UniqueArrivalKey,
                                flightKeys: Vector[(String, String, String, Timestamp)])
                               (implicit mat: Materializer): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] = {
    val eventualMaybePaxProfiles: Future[Seq[Try[List[ManifestPassengerProfile]]]] = Source(paxForArrivalQuery(flightKeys))
      .mapAsync(1) { builder =>
        paxInfoTable.db
          .run(builder.as[(String, String, String, String, String, Boolean, String)])
          .map { rows =>
            Success(passengerProfiles(rows).toList)
          }
          .recover { case t =>
            log.error(s"Didn't get pax for $arrivalToLookUp", t)
            Failure(t)
          }
      }
      .withAttributes(StreamSupervision.resumeStrategyWithLog(getClass.getName))
      .runWith(Sink.seq)

    eventualMaybePaxProfiles.map { tries =>
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

  def manifestSearch(uniqueArrivalKey: UniqueArrivalKey,
                     queries: List[(String, QueryFunction)])
                    (implicit mat: Materializer): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] = queries match {
    case Nil => Future((uniqueArrivalKey, None))
    case (_, nextQuery) :: tail =>
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

  override def maybeBestAvailableManifest(arrivalPort: PortCode,
                                          departurePort: PortCode,
                                          voyageNumber: VoyageNumber,
                                          scheduled: SDateLike)
                                         (implicit mat: Materializer): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] =
    manifestSearch(UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), queryHierarchy)

  type QueryFunction = UniqueArrivalKey => SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), paxInfoTable.tables.profile.api.Effect]

  private val queryHierarchy: List[(String, QueryFunction)] = List(
    ("sameFlightAndDay3WeekWindowPreviousYearQuery", sameFlightAndDay3WeekWindowPreviousYearQuery),
    ("sameFlight3WeekWindowPreviousYearQuery", sameFlight3WeekWindowPreviousYearQuery),
    ("sameRouteAndDay3WeekWindowPreviousYearQuery", sameRouteAndDay3WeekWindowPreviousYearQuery)
  )

  def sameFlightAndDay3WeekWindowPreviousYearQuery(uniqueArrivalKey: UniqueArrivalKey): SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), Effect] = {
    val lastYear = uniqueArrivalKey.scheduled.addMonths(-12)
    val earliestWeek = lastYear.addDays(-7)
    val latestWeek = lastYear.addDays(7)
    val scheduledTs = uniqueArrivalKey.scheduled.toISODateOnly
    val earliestTs = earliestWeek.toISODateOnly
    val middleTs = lastYear.toISODateOnly
    val latestTs = latestWeek.toISODateOnly

    sql"""SELECT
            arrival_port_code,
            departure_port_code,
            voyage_number,
            scheduled_date
          FROM
            voyage_manifest_passenger_info
          WHERE
            event_code ='DC'
            and arrival_port_code=${uniqueArrivalKey.arrivalPort.toString}
            and departure_port_code=${uniqueArrivalKey.departurePort.toString}
            and voyage_number=${uniqueArrivalKey.voyageNumber.numeric}
            and day_of_week = EXTRACT(DOW FROM TIMESTAMP '#$scheduledTs')::int
            and week_of_year IN (EXTRACT(WEEK FROM TIMESTAMP '#$earliestTs')::int, EXTRACT(WEEK FROM TIMESTAMP '#$middleTs')::int, EXTRACT(WEEK FROM TIMESTAMP '#$latestTs')::int)
          GROUP BY
            arrival_port_code,
            departure_port_code,
            voyage_number,
            scheduled_date
          """.as[(String, String, String, Timestamp)]
  }


  def sameFlight3WeekWindowPreviousYearQuery(uniqueArrivalKey: UniqueArrivalKey): SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), Effect] = {
    val lastYear = uniqueArrivalKey.scheduled.addMonths(-12)
    val earliestWeek = lastYear.addDays(-7)
    val latestWeek = lastYear.addDays(7)
    val earliestTs = earliestWeek.toISODateOnly
    val middleTs = lastYear.toISODateOnly
    val latestTs = latestWeek.toISODateOnly
    sql"""SELECT
            arrival_port_code,
            departure_port_code,
            voyage_number,
            scheduled_date
          FROM
            voyage_manifest_passenger_info
          WHERE
            event_code ='DC'
            and arrival_port_code=${uniqueArrivalKey.arrivalPort.toString}
            and departure_port_code=${uniqueArrivalKey.departurePort.toString}
            and voyage_number=${uniqueArrivalKey.voyageNumber.numeric}
            and week_of_year IN (EXTRACT(WEEK FROM TIMESTAMP '#$earliestTs')::int, EXTRACT(WEEK FROM TIMESTAMP '#$middleTs')::int, EXTRACT(WEEK FROM TIMESTAMP '#$latestTs')::int)
          GROUP BY
            arrival_port_code,
            departure_port_code,
            voyage_number,
            scheduled_date
          """.as[(String, String, String, Timestamp)]
  }

  def sameRouteAndDay3WeekWindowPreviousYearQuery(uniqueArrivalKey: UniqueArrivalKey): SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), Effect] = {
    val lastYear = uniqueArrivalKey.scheduled.addMonths(-12)
    val earliestWeek = lastYear.addDays(-7)
    val latestWeek = lastYear.addDays(7)
    val scheduledTs = uniqueArrivalKey.scheduled.toISODateOnly
    val earliestTs = earliestWeek.toISODateOnly
    val middleTs = lastYear.toISODateOnly
    val latestTs = latestWeek.toISODateOnly
    sql"""SELECT
            arrival_port_code,
            departure_port_code,
            voyage_number,
            scheduled_date
          FROM
            voyage_manifest_passenger_info
          WHERE
            event_code ='DC'
            and arrival_port_code=${uniqueArrivalKey.arrivalPort.toString}
            and departure_port_code=${uniqueArrivalKey.departurePort.toString}
            and day_of_week = EXTRACT(DOW FROM TIMESTAMP '#$scheduledTs')::int
            and week_of_year IN (EXTRACT(WEEK FROM TIMESTAMP '#$earliestTs')::int, EXTRACT(WEEK FROM TIMESTAMP '#$middleTs')::int, EXTRACT(WEEK FROM TIMESTAMP '#$latestTs')::int)
          GROUP BY
            arrival_port_code,
            departure_port_code,
            voyage_number,
            scheduled_date
          LIMIT 3
          """.as[(String, String, String, Timestamp)]
  }

  def paxForArrivalQuery(flightKeys: Vector[(String, String, String, Timestamp)]): Vector[SQLActionBuilder] =
    flightKeys.map {
      case (destination, origin, voyageNumberString, scheduled) =>
        val voyageNumber = VoyageNumber(voyageNumberString)
        sql"""select
            nationality_country_code,
            document_type,
            age,
            in_transit_flag,
            disembarkation_port_country_code,
            in_transit,
            passenger_identifier
          from voyage_manifest_passenger_info
          where
            event_code ='DC'
            and arrival_port_code=$destination
            and departure_port_code=$origin
            and voyage_number=${voyageNumber.numeric}
            and scheduled_date=$scheduled"""
    }

  def passengerProfiles(rows: Vector[(String, String, String, String, String, Boolean, String)]): Vector[ManifestPassengerProfile] = rows.map {
    case (nat, doc, age, transitFlag, endCountry, inTransit, identifier) =>
      val transit = (transitFlag, endCountry, inTransit) match {
        case (t, _, _) if t == "Y" => true
        case (_, c, _) if c != "GBR" => true
        case (_, _, t) if t => true
        case _ => false
      }
      val maybeIdentifier = if (identifier.nonEmpty) Option(identifier) else None
      ManifestPassengerProfile(Nationality(nat), Option(DocumentType(doc)), Try(PaxAge(age.toInt)).toOption, Option(transit), maybeIdentifier)
  }
}
