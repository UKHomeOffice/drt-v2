package drt.server.feeds.api

import drt.shared.CrunchApi.MillisSinceEpoch
import manifests.UniqueArrivalKey
import org.joda.time.DateTimeZone
import org.slf4j.{Logger, LoggerFactory}
import slick.sql.SqlStreamingAction
import slickdb.Tables
import uk.gov.homeoffice.drt.arrivals.VoyageNumber
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.{ExecutionContext, Future}

trait ManifestArrivalKeys {
  def nextKeys(since: MillisSinceEpoch): Future[(Option[MillisSinceEpoch], Iterable[UniqueArrivalKey])]
}

case class DbManifestArrivalKeys(tables: Tables, destinationPortCode: PortCode)
                                (implicit ec: ExecutionContext) extends ManifestArrivalKeys {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import tables.profile.api._

  override def nextKeys(since: Long): Future[(Option[MillisSinceEpoch], Iterable[UniqueArrivalKey])] = {
    val ts = SDate(since).toISOString.dropRight(1) + "." + SDate(since).millisSinceEpoch.toString.takeRight(3) + "Z"

    tables.run(zipQuery(ts))
      .flatMap {
        case Some((zipFileName, processedAt)) =>
          tables.run(jsonQuery(zipFileName))
            .flatMap { jsonFileNames =>
              tables.run(manifestsQuery(jsonFileNames)).map { result =>
                (Option(processedAt), result)
              }
            }
        case None =>
          Future.successful((None, Vector()))
      }
      .recover {
        case t =>
          log.error(s"Failed to get next keys from DB", t)
          (None, Vector())
      }
  }

  def zipQuery(ts: String): DBIOAction[Option[(String, MillisSinceEpoch)], NoStream, Effect] =
    sql"""SELECT pz.zip_file_name, EXTRACT(EPOCH FROM pz.processed_at) * 1000 FROM processed_zip pz WHERE pz.processed_at > TIMESTAMP '#$ts' ORDER BY pz.processed_at LIMIT 1
         |""".stripMargin
      .as[(String, Long)]
      .map(_.headOption)

  def jsonQuery(zipFileName: String): DBIOAction[Vector[String], NoStream, Effect] =
    sql"""SELECT pj.json_file_name FROM processed_json pj WHERE pj.zip_file_name=$zipFileName
         |""".stripMargin
      .as[String]

  def manifestsQuery(jsonFileNames: Iterable[String]): DBIOAction[Vector[UniqueArrivalKey], NoStream, Effect] =
    sql"""SELECT vm.departure_port_code, vm.voyage_number, EXTRACT(EPOCH FROM vm.scheduled_date) * 1000
         |FROM voyage_manifest_passenger_info vm
         |WHERE json_file IN ('#${jsonFileNames.mkString("','")}')
         |AND vm.arrival_port_code = ${destinationPortCode.iata}
         |AND event_code = 'DC'
         GROUP BY vm.departure_port_code, vm.voyage_number, vm.scheduled_date;
         |""".stripMargin
      .as[(String, Int, Long)]
      .map {
        _.map {
          case (origin, voyageNumber, scheduled) =>
            UniqueArrivalKey(destinationPortCode, PortCode(origin), VoyageNumber(voyageNumber), SDate(scheduled, DateTimeZone.UTC))
        }
      }
}
