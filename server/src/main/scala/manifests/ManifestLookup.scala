package manifests

import akka.stream.Materializer
import manifests.passengers.{BestAvailableManifest, ManifestPassengerProfile, ManifestPaxCount}
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import slick.sql.SqlStreamingAction
import slickdb.Tables
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.{Arrival, FeedArrival, VoyageNumber}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources
import uk.gov.homeoffice.drt.ports.{PaxAge, PortCode}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import java.sql.Timestamp
import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try


trait ManifestLookupLike {
  def maybeBestAvailableManifest(arrivalPort: PortCode,
                                 departurePort: PortCode,
                                 voyageNumber: VoyageNumber,
                                 scheduled: SDateLike): Future[(UniqueArrivalKey, Option[BestAvailableManifest])]

  def maybeHistoricManifestPax(arrivalPort: PortCode,
                               departurePort: PortCode,
                               voyageNumber: VoyageNumber,
                               scheduled: SDateLike): Future[(UniqueArrivalKey, Option[ManifestPaxCount])]
}

case class UniqueArrivalKey(arrivalPort: PortCode,
                            departurePort: PortCode,
                            voyageNumber: VoyageNumber,
                            scheduled: SDateLike) {
  override def toString: String = s"$departurePort -> $arrivalPort: $voyageNumber @ ${scheduled.toISOString}"
}

object UniqueArrivalKey {
  def apply(arrival: Arrival, port: PortCode): UniqueArrivalKey =
    UniqueArrivalKey(port, arrival.Origin, arrival.VoyageNumber, SDate(arrival.Scheduled))

  def apply(feedArrival: FeedArrival, port: PortCode): UniqueArrivalKey =
    UniqueArrivalKey(port, PortCode(feedArrival.origin), VoyageNumber(feedArrival.voyageNumber), SDate(feedArrival.scheduled))
}

case class ManifestLookup(tables: Tables)
                         (implicit mat: Materializer) extends ManifestLookupLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import tables.profile.api._

  override def maybeBestAvailableManifest(arrivalPort: PortCode,
                                          departurePort: PortCode,
                                          voyageNumber: VoyageNumber,
                                          scheduled: SDateLike
                                         ): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] = {
    val key = UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled)
    historicManifestSearch(key, queryHierarchy)
      .recover {
        case t =>
          log.error(s"Error looking up manifest for ${key.toString}", t)
          (key, None)
      }
  }

  private def manifestsForScheduled(flightKeys: Vector[(String, String, String, Timestamp)]): Future[Seq[ManifestPassengerProfile]] =
    if (flightKeys.nonEmpty)
      paxForArrivalQuery(flightKeys)
    else
      Future(Vector.empty)

  private def manifestPaxForScheduled(flightKeys: Vector[(String, String, String, Timestamp)]): Future[Option[(Int, Int)]] =
    manifestsForScheduled(flightKeys).map {
      case manifests if manifests.nonEmpty =>
        val totalPax = manifests.size / manifests.size
        val transPax = manifests.count(_.inTransit) / manifests.size
        Option(totalPax, transPax)
      case _ => None
    }

  private def historicManifestSearch(uniqueArrivalKey: UniqueArrivalKey,
                                     queries: List[(String, QueryFunction)])
                                    (implicit mat: Materializer): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] = {
    val startTime = SDate.now()
    findFlights(uniqueArrivalKey, queries, 1)
      .flatMap { flightKeys =>
        manifestsForScheduled(flightKeys)
          .map(profiles => (uniqueArrivalKey, maybeManifestFromProfiles(uniqueArrivalKey, profiles)))
      }
      .map { res =>
        val timeTaken = SDate.now().millisSinceEpoch - startTime.millisSinceEpoch
        if (timeTaken > 1000)
          log.warn(s"Historic manifest pax profile for $uniqueArrivalKey took ${timeTaken}ms")
        res
      }
  }

  private def historicManifestSearchForPaxCount(uniqueArrivalKey: UniqueArrivalKey,
                                                queries: List[(String, QueryFunction)])
                                               (implicit mat: Materializer): Future[(UniqueArrivalKey, Option[ManifestPaxCount])] = {
    val startTime = SDate.now()
    findFlights(uniqueArrivalKey, queries, 1).flatMap { flightKeys =>
      manifestPaxForScheduled(flightKeys)
        .map(maybePaxCount => (uniqueArrivalKey, maybePaxCount.map { case (totalPax, transPax) =>
          ManifestPaxCount(SplitSources.Historical, uniqueArrivalKey, totalPax, transPax)
        }))
    }.map { res =>
      val timeTaken = SDate.now().millisSinceEpoch - startTime.millisSinceEpoch
      if (timeTaken > 1000)
        log.warn(s"Historic manifest pax count for $uniqueArrivalKey took ${timeTaken}ms")
      res
    }
  }

  private def findFlights(uniqueArrivalKey: UniqueArrivalKey,
                          queries: List[(String, QueryFunction)],
                          queryNumber: Int,
                         )
                         (implicit mat: Materializer): Future[Vector[(String, String, String, Timestamp)]] = queries match {
    case Nil => Future(Vector.empty)
    case (_, nextQuery) :: tail =>
      val startTime = SDate.now()
      tables
        .run(nextQuery(uniqueArrivalKey))
        .recover {
          case t =>
            log.error(s"Error looking up manifest for ${uniqueArrivalKey.toString}", t)
            Vector.empty
        }
        .flatMap {
          case flightsFound if flightsFound.nonEmpty =>
//            println(s"\nFound ${flightsFound.size} historic manifests for $uniqueArrivalKey query $queryNumber")
            Future(flightsFound)
          case _ =>
//            println(s"\nNo results for $uniqueArrivalKey with query ${3 - tail.size}. ${tail.size} queries left.")
            findFlights(uniqueArrivalKey, tail, queryNumber + 1)
        }.map { res =>
          val timeTaken = SDate.now().millisSinceEpoch - startTime.millisSinceEpoch
          if (timeTaken > 1000)
            log.warn(s"Historic manifest query $queryNumber for $uniqueArrivalKey took ${timeTaken}ms")
          res
        }
  }

  private def maybeManifestFromProfiles(uniqueArrivalKey: UniqueArrivalKey,
                                        profiles: immutable.Seq[ManifestPassengerProfile],
                                       ): Option[BestAvailableManifest] = {
    if (profiles.nonEmpty)
      Some(BestAvailableManifest(SplitSources.Historical, uniqueArrivalKey, profiles.toList))
    else None
  }

  private type QueryFunction =
    UniqueArrivalKey => SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), tables.profile.api.Effect]

  private val queryHierarchy: List[(String, QueryFunction)] = List(
    ("sameFlightAndDay3WeekWindowPreviousYearQuery", sameFlightAndDay3WeekWindowPreviousYearQuery),
    ("sameFlight3WeekWindowPreviousYearQuery", sameFlight3WeekWindowPreviousYearQuery),
    ("sameRouteAndDay3WeekWindowPreviousYearQuery", sameRouteAndDay3WeekWindowPreviousYearQuery)
  )

  private def sameFlightAndDay3WeekWindowPreviousYearQuery(uniqueArrivalKey: UniqueArrivalKey): SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), Effect] = {
    val scheduled = uniqueArrivalKey.scheduled.toISODateOnly

    sql"""SELECT
            arrival_port_code,
            departure_port_code,
            voyage_number,
            scheduled
          FROM processed_json
          WHERE
            event_code ='DC'
            and arrival_port_code=${uniqueArrivalKey.arrivalPort.toString}
            and departure_port_code=${uniqueArrivalKey.departurePort.toString}
            and voyage_number=${uniqueArrivalKey.voyageNumber.numeric}
            and EXTRACT(DOW FROM scheduled) = EXTRACT(DOW FROM TIMESTAMP '#$scheduled')::int
            and EXTRACT(WEEK FROM scheduled) IN (EXTRACT(WEEK FROM TIMESTAMP '#$scheduled' - interval '1 week')::int, EXTRACT(WEEK FROM TIMESTAMP '#$scheduled')::int, EXTRACT(WEEK FROM TIMESTAMP '#$scheduled' + interval '1 week')::int)
            and EXTRACT(YEAR FROM scheduled) IN (EXTRACT(YEAR FROM TIMESTAMP '#$scheduled' - interval '2 year')::int, EXTRACT(YEAR FROM TIMESTAMP '#$scheduled' - interval '1 year')::int, EXTRACT(YEAR FROM TIMESTAMP '#$scheduled')::int)
          GROUP BY
            arrival_port_code,
            departure_port_code,
            voyage_number,
            scheduled
          ORDER BY scheduled DESC
          LIMIT 6
          """.as[(String, String, String, Timestamp)]
  }

  /**
   SELECT
   arrival_port_code,
   departure_port_code,
   voyage_number,
   scheduled
   FROM processed_json
   WHERE
   event_code ='DC'
   and arrival_port_code='STN'
   and departure_port_code='BJV'
   and voyage_number=1416
   and EXTRACT(DOW FROM scheduled) = EXTRACT(DOW FROM TIMESTAMP '2024-05-14')::int
   and EXTRACT(WEEK FROM scheduled) IN (EXTRACT(WEEK FROM TIMESTAMP '2024-05-14' - interval '1 week')::int, EXTRACT(WEEK FROM TIMESTAMP '2024-05-14')::int, EXTRACT(WEEK FROM TIMESTAMP '2024-05-14' + interval '1 week')::int)
   and EXTRACT(YEAR FROM scheduled) IN (EXTRACT(YEAR FROM TIMESTAMP '2024-05-14' - interval '2 year')::int, EXTRACT(YEAR FROM TIMESTAMP '2024-05-14' - interval '1 year')::int, EXTRACT(YEAR FROM TIMESTAMP '2024-05-14')::int)
   GROUP BY
   arrival_port_code,
   departure_port_code,
   voyage_number,
   scheduled
   ORDER BY scheduled DESC
   LIMIT 6
   */

  private def sameFlight3WeekWindowPreviousYearQuery(uniqueArrivalKey: UniqueArrivalKey): SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), Effect] = {
    val scheduled = uniqueArrivalKey.scheduled.toISODateOnly
    sql"""SELECT
            arrival_port_code,
            departure_port_code,
            voyage_number,
            scheduled
          FROM
            processed_json
          WHERE
            event_code ='DC'
            and arrival_port_code=${uniqueArrivalKey.arrivalPort.toString}
            and departure_port_code=${uniqueArrivalKey.departurePort.toString}
            and voyage_number=${uniqueArrivalKey.voyageNumber.numeric}
            and EXTRACT(WEEK FROM scheduled) IN (EXTRACT(WEEK FROM TIMESTAMP '#$scheduled' - interval '1 week')::int, EXTRACT(WEEK FROM TIMESTAMP '#$scheduled')::int, EXTRACT(WEEK FROM TIMESTAMP '#$scheduled' + interval '1 week')::int)
            and EXTRACT(YEAR FROM scheduled) IN (EXTRACT(YEAR FROM TIMESTAMP '#$scheduled' - interval '2 year')::int, EXTRACT(YEAR FROM TIMESTAMP '#$scheduled' - interval '1 year')::int, EXTRACT(YEAR FROM TIMESTAMP '#$scheduled')::int)
          GROUP BY
            arrival_port_code,
            departure_port_code,
            voyage_number,
            scheduled
          ORDER BY scheduled DESC
          LIMIT 6
          """.as[(String, String, String, Timestamp)]
  }

  /**
   SELECT
   arrival_port_code,
   departure_port_code,
   voyage_number,
   scheduled
   FROM processed_json
   WHERE
   event_code ='DC'
   and arrival_port_code='STN'
   and departure_port_code='BJV'
   and voyage_number=1416
   and EXTRACT(WEEK FROM scheduled) IN (EXTRACT(WEEK FROM TIMESTAMP '2024-05-14' - interval '1 week')::int, EXTRACT(WEEK FROM TIMESTAMP '2024-05-14')::int, EXTRACT(WEEK FROM TIMESTAMP '2024-05-14' + interval '1 week')::int)
   and EXTRACT(YEAR FROM scheduled) IN (EXTRACT(YEAR FROM TIMESTAMP '2024-05-14' - interval '2 year')::int, EXTRACT(YEAR FROM TIMESTAMP '2024-05-14' - interval '1 year')::int, EXTRACT(YEAR FROM TIMESTAMP '2024-05-14')::int)
   GROUP BY
   arrival_port_code,
   departure_port_code,
   voyage_number,
   scheduled
   ORDER BY scheduled DESC
   LIMIT 6
   */

  private def sameRouteAndDay3WeekWindowPreviousYearQuery(uniqueArrivalKey: UniqueArrivalKey): SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), Effect] = {
    val scheduled = uniqueArrivalKey.scheduled.toISODateOnly
    sql"""SELECT
            arrival_port_code,
            departure_port_code,
            voyage_number,
            scheduled
          FROM
            processed_json
          WHERE
            event_code ='DC'
            and arrival_port_code=${uniqueArrivalKey.arrivalPort.toString}
            and departure_port_code=${uniqueArrivalKey.departurePort.toString}
            and EXTRACT(WEEK FROM scheduled) IN (EXTRACT(WEEK FROM TIMESTAMP '#$scheduled' - interval '1 week')::int, EXTRACT(WEEK FROM TIMESTAMP '#$scheduled')::int, EXTRACT(WEEK FROM TIMESTAMP '#$scheduled' + interval '1 week')::int)
            and EXTRACT(YEAR FROM scheduled) IN (EXTRACT(YEAR FROM TIMESTAMP '#$scheduled' - interval '2 year')::int, EXTRACT(YEAR FROM TIMESTAMP '#$scheduled' - interval '1 year')::int, EXTRACT(YEAR FROM TIMESTAMP '#$scheduled')::int)
          GROUP BY
            arrival_port_code,
            departure_port_code,
            voyage_number,
            scheduled
          ORDER BY scheduled DESC
          LIMIT 6
          """.as[(String, String, String, Timestamp)]
  }

  /**
   SELECT
   arrival_port_code,
   departure_port_code,
   voyage_number,
   scheduled
   FROM
   processed_json
   WHERE
   event_code ='DC'
   and arrival_port_code='STN'
   and departure_port_code='BJV'
   and EXTRACT(WEEK FROM scheduled) IN (EXTRACT(WEEK FROM TIMESTAMP '2024-05-14' - interval '1 week')::int, EXTRACT(WEEK FROM TIMESTAMP '2024-05-14')::int, EXTRACT(WEEK FROM TIMESTAMP '2024-05-14' + interval '1 week')::int)
   and EXTRACT(YEAR FROM scheduled) IN (EXTRACT(YEAR FROM TIMESTAMP '2024-05-14' - interval '2 year')::int, EXTRACT(YEAR FROM TIMESTAMP '2024-05-14' - interval '1 year')::int, EXTRACT(YEAR FROM TIMESTAMP '2024-05-14')::int)
   GROUP BY
   arrival_port_code,
   departure_port_code,
   voyage_number,
   scheduled
   ORDER BY scheduled DESC
   LIMIT 6
   */

  private def paxForArrivalQuery(flightKeys: Vector[(String, String, String, Timestamp)]): Future[Seq[ManifestPassengerProfile]] = {
    val q = tables.VoyageManifestPassengerInfo
      .filter { vm =>
        vm.event_code === "DC" && flightKeys.map {
          case (destination, origin, voyageNumberString, scheduled) =>
            val voyageNumber = VoyageNumber(voyageNumberString)
              vm.arrival_port_code === destination &&
              vm.departure_port_code === origin &&
              vm.voyage_number === voyageNumber.numeric &&
              vm.scheduled_date === scheduled
        }.reduce(_ || _)
      }
      .map(vm => (vm.nationality_country_code, vm.document_type, vm.age, vm.in_transit_flag, vm.disembarkation_port_country_code, vm.in_transit, vm.passenger_identifier))
      .result

    tables.run(q)
      .map { rows =>
        val identifiersExist = rows.exists(_._7.nonEmpty)
        rows.filter(r => if (identifiersExist) r._7.nonEmpty else true).map {
          case (nat, doc, age, transitFlag, endCountry, inTransit, identifier) =>
            val transit = (transitFlag, endCountry, inTransit) match {
              case (t, _, _) if t == "Y" => true
              case (_, c, _) if c != "GBR" => true
              case (_, _, t) if t => true
              case _ => false
            }
            val maybeIdentifier = if (identifier.nonEmpty) Option(identifier) else None
            ManifestPassengerProfile(Nationality(nat), Option(DocumentType(doc)), Try(PaxAge(age)).toOption, transit, maybeIdentifier)
        }
      }
  }

//  private def paxForArrivalQuery_(flightKeys: Vector[(String, String, String, Timestamp)]): Vector[SQLActionBuilder] =
//    flightKeys.map {
//      case (destination, origin, voyageNumberString, scheduled) =>
//        val voyageNumber = VoyageNumber(voyageNumberString)
//        sql"""select
//            nationality_country_code,
//            document_type,
//            age,
//            in_transit_flag,
//            disembarkation_port_country_code,
//            in_transit,
//            passenger_identifier
//          from voyage_manifest_passenger_info
//          where
//            event_code ='DC'
//            and arrival_port_code=$destination
//            and departure_port_code=$origin
//            and voyage_number=${voyageNumber.numeric}
//            and scheduled_date=$scheduled"""
//    }

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

  override def maybeHistoricManifestPax(arrivalPort: PortCode, departurePort: PortCode, voyageNumber: VoyageNumber, scheduled: SDateLike): Future[(UniqueArrivalKey, Option[ManifestPaxCount])] =
    historicManifestSearchForPaxCount(UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), queryHierarchy)
}
