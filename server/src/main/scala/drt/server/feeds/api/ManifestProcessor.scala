package drt.server.feeds.api

import akka.Done
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.shared.CrunchApi.MillisSinceEpoch
import manifests.UniqueArrivalKey
import org.slf4j.LoggerFactory
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser._
import server.feeds.{DqManifests, ManifestsFeedResponse, ManifestsFeedSuccess}
import uk.gov.homeoffice.drt.time.SDate
import slickdb.Tables
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.CarrierCode
import uk.gov.homeoffice.drt.arrivals.EventTypes.DC
import uk.gov.homeoffice.drt.ports.{PaxAge, PortCode}

import scala.concurrent.{ExecutionContext, Future}

trait ManifestProcessor {
  def process(uniqueArrivalKey: UniqueArrivalKey, processedAt: MillisSinceEpoch): Future[Done]
  def reportNoNewData(processedAt: MillisSinceEpoch): Future[Done]
}

case class DbManifestProcessor(tables: Tables,
                               destinationPortCode: PortCode,
                               manifestsLiveResponse: SourceQueueWithComplete[ManifestsFeedResponse])
                              (implicit ec: ExecutionContext) extends ManifestProcessor {
  private val log = LoggerFactory.getLogger(getClass)

  import tables.profile.api._

  override def reportNoNewData(processedAt: MillisSinceEpoch): Future[Done] =
    manifestsLiveResponse
      .offer(ManifestsFeedSuccess(DqManifests(processedAt, Seq())))
      .map(_ => Done)

  override def process(uniqueArrivalKey: UniqueArrivalKey, processedAt: MillisSinceEpoch): Future[Done] =
    manifestForArrivalKey(uniqueArrivalKey).flatMap {
      case None =>
        log.warn(s"No manifest entries found for $uniqueArrivalKey")
        Future.successful(Done)

      case Some(manifest) =>
        val response = ManifestsFeedSuccess(DqManifests(processedAt, Seq(manifest)))
        manifestsLiveResponse
          .offer(response)
          .map(_ => Done)
    }

  private def manifestForArrivalKey(uniqueArrivalKey: UniqueArrivalKey): Future[Option[VoyageManifest]] = {
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

    tables.run(query).map {
      case pax if pax.isEmpty => None
      case pax =>
        Option(VoyageManifest(
          DC,
          uniqueArrivalKey.arrivalPort,
          uniqueArrivalKey.departurePort,
          uniqueArrivalKey.voyageNumber,
          CarrierCode(""),
          ManifestDateOfArrival(uniqueArrivalKey.scheduled.toISODateOnly),
          ManifestTimeOfArrival(uniqueArrivalKey.scheduled.toHoursAndMinutes),
          pax.toList
        ))
    }
      .recover {
        case t =>
          log.error(s"Failed to execute query", t)
          None
      }
  }
}
