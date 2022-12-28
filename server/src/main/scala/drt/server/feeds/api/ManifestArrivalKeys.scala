package drt.server.feeds.api

import drt.shared.CrunchApi.MillisSinceEpoch
import manifests.UniqueArrivalKey
import org.joda.time.DateTimeZone
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.time.SDate
import slickdb.Tables
import uk.gov.homeoffice.drt.arrivals.VoyageNumber
import uk.gov.homeoffice.drt.ports.PortCode

import scala.concurrent.{ExecutionContext, Future}

trait ManifestArrivalKeys {
  def nextKeys(since: MillisSinceEpoch): Future[Iterable[(UniqueArrivalKey, MillisSinceEpoch)]]
}

case class DbManifestArrivalKeys(tables: Tables, destinationPortCode: PortCode)
                                (implicit ec: ExecutionContext) extends ManifestArrivalKeys {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import tables.profile.api._

  override def nextKeys(since: Long): Future[Iterable[(UniqueArrivalKey, MillisSinceEpoch)]] = {
    val ts = SDate(since).toISOString()
    val query: DBIOAction[Vector[(UniqueArrivalKey, MillisSinceEpoch)], NoStream, Effect] =
      sql"""SELECT
              vm.departure_port_code, vm.voyage_number, EXTRACT(EPOCH FROM vm.scheduled_date) * 1000, EXTRACT(EPOCH FROM pz.processed_at) * 1000
            FROM processed_zip pz
            INNER JOIN processed_json pj ON pz.zip_file_name = pj.zip_file_name
            INNER JOIN voyage_manifest_passenger_info vm ON vm.json_file = pj.json_file_name
            WHERE
              pz.processed_at > TIMESTAMP '#$ts'
              AND vm.arrival_port_code = ${destinationPortCode.iata}
              AND event_code = 'DC'
            GROUP BY vm.departure_port_code, vm.voyage_number, vm.scheduled_date, pz.processed_at
            ORDER BY pz.processed_at
            """.stripMargin
        .as[(String, Int, Long, Long)].map {
        _.map {
          case (origin, voyageNumber, scheduled, processedAt) =>
            val key = UniqueArrivalKey(destinationPortCode, PortCode(origin), VoyageNumber(voyageNumber), SDate(scheduled, DateTimeZone.UTC))
            (key, SDate(processedAt, DateTimeZone.UTC).millisSinceEpoch)
        }
      }

    tables.run(query)
  }
}
