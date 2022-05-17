package manifests

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import manifests.passengers.{BestAvailableManifest, HistoricManifestPax, ManifestPassengerProfile}
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import services.{SDate, StreamSupervision}
import slick.jdbc.SQLActionBuilder
import slick.sql.SqlStreamingAction
import slickdb.Tables
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.VoyageNumber
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources
import uk.gov.homeoffice.drt.ports.{PaxAge, PortCode}
import uk.gov.homeoffice.drt.time.SDateLike

import java.sql.Timestamp
import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait ManifestLookupLike {
  def maybeBestAvailableManifest(arrivalPort: PortCode,
                                 departurePort: PortCode,
                                 voyageNumber: VoyageNumber,
                                 scheduled: SDateLike): Future[(UniqueArrivalKey, Option[BestAvailableManifest])]

  def historicManifestPax(arrivalPort: PortCode,
                          departurePort: PortCode,
                          voyageNumber: VoyageNumber,
                          scheduled: SDateLike): Future[(UniqueArrivalKey, Option[HistoricManifestPax])]
}

case class UniqueArrivalKey(arrivalPort: PortCode,
                            departurePort: PortCode,
                            voyageNumber: VoyageNumber,
                            scheduled: SDateLike) {
  override def toString: String = s"$arrivalPort -> $departurePort: $voyageNumber @ ${scheduled.toISOString()}"

  val queryArrivalKey: (String, String, String, Timestamp) =
    (arrivalPort.iata, departurePort.iata, voyageNumber.numeric.toString, new Timestamp(scheduled.millisSinceEpoch))
}

case class ManifestLookup(tables: Tables)
                         (implicit mat: Materializer) extends ManifestLookupLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import tables.profile.api._

  override def maybeBestAvailableManifest(arrivalPort: PortCode,
                                          departurePort: PortCode,
                                          voyageNumber: VoyageNumber,
                                          scheduled: SDateLike): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] =
    historicManifestSearch(UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), queryHierarchy)

  private def manifestTriesForScheduled(flightKeys: Vector[(String, String, String, Timestamp)])
                                       (implicit mat: Materializer): Future[immutable.Seq[ManifestPassengerProfile]] = {
    val eventualMaybePaxProfiles = Source(paxForArrivalQuery(flightKeys))
      .mapAsync(1)(paxProfilesFromQuery)
      .withAttributes(StreamSupervision.resumeStrategyWithLog(getClass.getName))
      .runWith(Sink.seq)

    eventualMaybePaxProfiles.map { tries =>
      tries.collect {
        case Failure(t) => log.warn(s"Failed to get manifests", t)
      }

      tries.collect { case Success(paxProfiles) => paxProfiles }.flatten
    }
  }

  private def paxProfilesFromQuery(builder: SQLActionBuilder): Future[Try[List[ManifestPassengerProfile]]] =
    tables
      .run(builder.as[(String, String, String, String, String, Boolean, String)])
      .map { rows =>
        Success(passengerProfiles(rows).toList)
      }
      .recover {
        case t => Failure(t)
      }

  private def historicManifestSearch(uniqueArrivalKey: UniqueArrivalKey,
                                     queries: List[(String, QueryFunction)])
                                    (implicit mat: Materializer): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] = queries.zipWithIndex match {
    case Nil => Future((uniqueArrivalKey, None))
    case ((_, nextQuery), queryNumber) :: tail =>
      val startTime = SDate.now()
      tables
        .run(nextQuery(uniqueArrivalKey))
        .flatMap {
          case flightsFound if flightsFound.nonEmpty =>
            manifestTriesForScheduled(flightsFound).map { profiles =>
              (uniqueArrivalKey, maybeManifestFromProfiles(uniqueArrivalKey, profiles))
            }

          case _ =>
            historicManifestSearch(uniqueArrivalKey, tail.map(_._1))
        }
        .map { res =>
          val timeTaken = SDate.now().millisSinceEpoch - startTime.millisSinceEpoch
          if (timeTaken > 1000)
            log.warn(s"Historic manifest query $queryNumber for $uniqueArrivalKey took ${timeTaken}ms")

          res
        }
  }

  private def historicManifestPaxCount(uniqueArrivalKey: UniqueArrivalKey,
                                        queries: List[(String, QueryFunction)])
                                       (implicit mat: Materializer): Future[(UniqueArrivalKey, Option[HistoricManifestPax])] = queries.zipWithIndex match {
    case Nil => Future((uniqueArrivalKey, None))
    case ((_, nextQuery), queryNumber) :: tail =>
      val startTime = SDate.now()
      tables
        .run(nextQuery(uniqueArrivalKey))
        .flatMap {
          case flightsFound if flightsFound.nonEmpty =>
            manifestTriesForScheduled(flightsFound).map { profiles =>
              (uniqueArrivalKey, maybeManifestPaxFromProfiles(uniqueArrivalKey, profiles))
            }
          case _ =>
            historicManifestPaxCount(uniqueArrivalKey, tail.map(_._1))
        }
        .map { res =>
          res
        }
  }

  private def maybeManifestFromProfiles(uniqueArrivalKey: UniqueArrivalKey, profiles: immutable.Seq[ManifestPassengerProfile]) = {
    if (profiles.nonEmpty)
      Option(BestAvailableManifest(SplitSources.Historical, uniqueArrivalKey, profiles.toList))
    else None
  }

  private def maybeManifestPaxFromProfiles(uniqueArrivalKey: UniqueArrivalKey, profiles: immutable.Seq[ManifestPassengerProfile]) = {
    if (profiles.nonEmpty) {
      Option(HistoricManifestPax(SplitSources.Historical, uniqueArrivalKey, profiles.toList.size))
    } else None
  }

  type QueryFunction = UniqueArrivalKey => SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), tables.profile.api.Effect]

  private val queryHierarchy: List[(String, QueryFunction)] = List(
    ("sameFlightAndDay3WeekWindowPreviousYearQuery", sameFlightAndDay3WeekWindowPreviousYearQuery),
    ("sameFlight3WeekWindowPreviousYearQuery", sameFlight3WeekWindowPreviousYearQuery),
    ("sameRouteAndDay3WeekWindowPreviousYearQuery", sameRouteAndDay3WeekWindowPreviousYearQuery)
  )

  private def sameFlightAndDay3WeekWindowPreviousYearQuery(uniqueArrivalKey: UniqueArrivalKey): SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), Effect] = {
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
          ORDER BY scheduled_date DESC
          LIMIT 6
          """.as[(String, String, String, Timestamp)]
  }

  private def sameFlight3WeekWindowPreviousYearQuery(uniqueArrivalKey: UniqueArrivalKey): SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), Effect] = {
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
          ORDER BY scheduled_date DESC
          LIMIT 6
          """.as[(String, String, String, Timestamp)]
  }

  private def sameRouteAndDay3WeekWindowPreviousYearQuery(uniqueArrivalKey: UniqueArrivalKey): SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), Effect] = {
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
          ORDER BY scheduled_date DESC
          LIMIT 6
          """.as[(String, String, String, Timestamp)]
  }

  private def paxForArrivalQuery(flightKeys: Vector[(String, String, String, Timestamp)]): Vector[SQLActionBuilder] =
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

  private def passengerProfiles(rows: Vector[(String, String, String, String, String, Boolean, String)]): Vector[ManifestPassengerProfile] = rows.map {
    case (nat, doc, age, transitFlag, endCountry, inTransit, identifier) =>
      val transit = (transitFlag, endCountry, inTransit) match {
        case (t, _, _) if t == "Y" => true
        case (_, c, _) if c != "GBR" => true
        case (_, _, t) if t => true
        case _ => false
      }
      val maybeIdentifier = if (identifier.nonEmpty) Option(identifier) else None
      ManifestPassengerProfile(Nationality(nat), Option(DocumentType(doc)), Try(PaxAge(age.toInt)).toOption, transit, maybeIdentifier)
  }

  override def historicManifestPax(arrivalPort: PortCode, departurePort: PortCode, voyageNumber: VoyageNumber, scheduled: SDateLike): Future[(UniqueArrivalKey, Option[HistoricManifestPax])] = {
    historicManifestPaxCount(UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), queryHierarchy)
  }
}
