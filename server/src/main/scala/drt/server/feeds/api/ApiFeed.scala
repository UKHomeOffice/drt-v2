package drt.server.feeds.api

import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.{Done, NotUsed}
import drt.shared.CrunchApi.MillisSinceEpoch
import manifests.UniqueArrivalKey
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser._
import server.feeds.{DqManifests, ManifestsFeedResponse, ManifestsFeedSuccess}
import services.SDate
import slickdb.VoyageManifestPassengerInfoTable
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.EventTypes.DC
import uk.gov.homeoffice.drt.arrivals.{CarrierCode, VoyageNumber}
import uk.gov.homeoffice.drt.ports.{PaxAge, PortCode}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

trait ManifestProcessor {
  def process(uniqueArrivalKey: UniqueArrivalKey, processedAt: MillisSinceEpoch): Future[Done]
}

case class DbManifestProcessor(paxInfoTable: VoyageManifestPassengerInfoTable,
                               destinationPortCode: PortCode,
                               manifestsLiveResponse: SourceQueueWithComplete[ManifestsFeedResponse])
                              (implicit ec: ExecutionContext) extends ManifestProcessor {

  import paxInfoTable.tables.profile.api._

  override def process(uniqueArrivalKey: UniqueArrivalKey, processedAt: MillisSinceEpoch): Future[Done] =
    manifestForArrivalKey(uniqueArrivalKey).flatMap { manifest =>
      val response = ManifestsFeedSuccess(DqManifests(processedAt, Seq(manifest)))
      manifestsLiveResponse
        .offer(response)
        .map(_ => Done)
    }

  def manifestForArrivalKey(uniqueArrivalKey: UniqueArrivalKey): Future[VoyageManifest] = {
    val scheduled = SDate(uniqueArrivalKey.scheduled.millisSinceEpoch).toISOString()
    val query =
      sql"""SELECT
           |  document_type,
           |  document_issuing_country_code,
           |  eea_flag,
           |  age,
           |  disembarkation_port_code,
           |  in_transit_flag,
           |  disembarkation_port_country_code,
           |  nationality_country_code,
           |  passenger_identifier
           |FROM voyage_manifest_passenger_info
           |WHERE
           |  event_code ='DC'
           |  and arrival_port_code=${uniqueArrivalKey.arrivalPort.iata}
           |  and departure_port_code=${uniqueArrivalKey.departurePort.iata}
           |  and voyage_number=${uniqueArrivalKey.voyageNumber.numeric}
           |  and scheduled_date = TIMESTAMP '#$scheduled'
           |""".stripMargin.as[(String, String, String, Int, String, String, String, String, String)]
        .map {
          _.map {
            case (dt, dcc, eea, age, disPc, it, disPcc, natCc, pId) =>
              PassengerInfoJson(
                DocumentType = Option(DocumentType(dt)),
                DocumentIssuingCountryCode = Nationality(dcc),
                EEAFlag = EeaFlag(eea),
                Age = Option(PaxAge(age)),
                DisembarkationPortCode = Option(PortCode(disPc)),
                InTransitFlag = InTransit(it),
                DisembarkationPortCountryCode = Option(Nationality(disPcc)),
                NationalityCountryCode = Option(Nationality(natCc)),
                PassengerIdentifier = if (pId.isEmpty) None else Option(pId)
              )
          }
        }

    paxInfoTable.db.run(query).map { pax =>
      VoyageManifest(
        DC,
        uniqueArrivalKey.arrivalPort,
        uniqueArrivalKey.departurePort,
        uniqueArrivalKey.voyageNumber,
        CarrierCode(""),
        ManifestDateOfArrival(uniqueArrivalKey.scheduled.toISODateOnly),
        ManifestTimeOfArrival(uniqueArrivalKey.scheduled.toHoursAndMinutes),
        pax.toList
      )
    }
  }
}

trait ManifestArrivalKeys {
  def nextKeys(since: MillisSinceEpoch): Future[Iterable[(UniqueArrivalKey, MillisSinceEpoch)]]
}

case class DbManifestArrivalKeys(paxInfoTable: VoyageManifestPassengerInfoTable, destinationPortCode: PortCode)
                                (implicit ec: ExecutionContext) extends ManifestArrivalKeys {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import paxInfoTable.tables.profile.api._

  override def nextKeys(since: Long): Future[Iterable[(UniqueArrivalKey, MillisSinceEpoch)]] = {
    val ts = SDate(since).toISOString()
    val query: DBIOAction[Vector[(UniqueArrivalKey, MillisSinceEpoch)], NoStream, Effect] =
      sql"""SELECT
              vm.departure_port_code, vm.voyage_number, vm.scheduled_date, pz.processed_at
            FROM processed_zip pz
            INNER JOIN processed_json pj ON pz.zip_file_name = pj.zip_file_name
            INNER JOIN voyage_manifest_passenger_info vm ON vm.json_file = pj.json_file_name
            WHERE
              pz.processed_at >= TIMESTAMP '#$ts'
              AND vm.arrival_port_code = ${destinationPortCode.iata}
              AND event_code = 'DC'
            GROUP BY vm.departure_port_code, vm.voyage_number, vm.scheduled_date, pz.processed_at
            ORDER BY pz.processed_at
            """.stripMargin
        .as[(String, Int, String, String)].map {
        _.map {
          case (origin, voyageNumber, scheduled, processedAt) =>
            val key = UniqueArrivalKey(destinationPortCode, PortCode(origin), VoyageNumber(voyageNumber), SDate(scheduled.replace(" ", "T")))
            (key, SDate(processedAt.replace(" ", "T")).millisSinceEpoch)
        }
      }

    paxInfoTable.db.run(query)
  }
}

trait ApiFeed {
  def processFilesAfter(since: MillisSinceEpoch): Source[Done, NotUsed]
}

case class ApiFeedImpl(arrivalKeyProvider: ManifestArrivalKeys,
                       manifestProcessor: ManifestProcessor,
                       throttle: FiniteDuration)
                      (implicit ec: ExecutionContext) extends ApiFeed {
  private val log = LoggerFactory.getLogger(getClass)

  override def processFilesAfter(since: MillisSinceEpoch): Source[Done, NotUsed] =
    Source
      .unfoldAsync((since, Iterable[(UniqueArrivalKey, MillisSinceEpoch)]())) { case (lastProcessedAt, lastKeys) =>
        markerAndNextArrivalKeys(lastProcessedAt).map {
          case (nextMarker, newKeys) =>
            if (newKeys.nonEmpty) log.info(s"${newKeys.size} new live manifests available. Next marker: ${SDate(nextMarker).toISOString()}")
            Option((nextMarker, newKeys), (lastProcessedAt, lastKeys))
        }
      }
      .throttle(1, throttle)
      .map { case (_, keys) => keys }
      .mapConcat(identity)
      .mapAsync(1) {
        case (uniqueArrivalKey, processedAt) =>
          manifestProcessor
            .process(uniqueArrivalKey, processedAt)
            .map { res =>
              log.info(s"Manifest successfully processed for $uniqueArrivalKey")
              res
            }
            .recover {
              case t =>
                log.error(s"Failed to process live manifest for $uniqueArrivalKey", t)
                Done
            }
      }

  private def markerAndNextArrivalKeys(since: MillisSinceEpoch): Future[(MillisSinceEpoch, Iterable[(UniqueArrivalKey, MillisSinceEpoch)])] =
    arrivalKeyProvider
      .nextKeys(since)
      .map { keys =>
        val keysToProcess = if (since > 0) keys.filterNot(_._2 == since) else keys
        val nextFetch = keysToProcess.map(_._2).toList.sorted.reverse.headOption.getOrElse(since)
        (nextFetch, keysToProcess)
      }
      .recover {
        case t =>
          log.error(s"Failed to get next arrival keys", t)
          (since, Seq())
      }
}
