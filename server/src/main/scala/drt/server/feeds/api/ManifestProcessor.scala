package drt.server.feeds.api

import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import drt.server.feeds.{DqManifests, ManifestsFeedResponse, ManifestsFeedSuccess}
import drt.shared.CrunchApi.MillisSinceEpoch
import manifests.UniqueArrivalKey
import org.slf4j.LoggerFactory
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser._
import slickdb.Tables
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.CarrierCode
import uk.gov.homeoffice.drt.arrivals.EventTypes.DC
import uk.gov.homeoffice.drt.ports.{PaxAge, PortCode}
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.{ExecutionContext, Future}

trait ManifestProcessor {
  def process(uniqueArrivalKeys: Seq[UniqueArrivalKey], processedAt: MillisSinceEpoch): Future[Done]

  def reportNoNewData(processedAt: MillisSinceEpoch): Future[Done]
}

case class DbManifestProcessor(tables: Tables,
                               destinationPortCode: PortCode,
                               persistManifests: ManifestsFeedResponse => Future[Done],
                              )
                              (implicit ec: ExecutionContext, mat: Materializer) extends ManifestProcessor {
  private val log = LoggerFactory.getLogger(getClass)

  import tables.profile.api._

  override def reportNoNewData(processedAt: MillisSinceEpoch): Future[Done] =
    persistManifests(ManifestsFeedSuccess(DqManifests(processedAt, Seq())))

  override def process(uniqueArrivalKeys: Seq[UniqueArrivalKey], processedAt: MillisSinceEpoch): Future[Done] = {
    Source(uniqueArrivalKeys.grouped(25).toList)
      .mapAsync(1) { keys =>
        Source(keys)
          .mapAsync(1)(manifestForArrivalKey)
          .collect {
            case Some(manifest) => manifest
          }
          .runWith(Sink.seq)
          .flatMap { manifests =>
            persistManifests(ManifestsFeedSuccess(DqManifests(processedAt, manifests)))
          }
      }
      .runWith(Sink.ignore)
  }

  private def manifestForArrivalKey(uniqueArrivalKey: UniqueArrivalKey): Future[Option[VoyageManifest]] = {
    val scheduled = SDate(uniqueArrivalKey.scheduled.millisSinceEpoch).toISOString
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
