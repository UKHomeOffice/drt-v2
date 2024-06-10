package drt.server.feeds.api

import drt.shared.CrunchApi.MillisSinceEpoch
import manifests.UniqueArrivalKey
import org.joda.time.DateTimeZone
import org.slf4j.{Logger, LoggerFactory}
import slickdb.AggregatedDbTables
import uk.gov.homeoffice.drt.arrivals.VoyageNumber
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.{ExecutionContext, Future}

trait ManifestArrivalKeys {
  def nextKeys(since: MillisSinceEpoch): Future[(Option[MillisSinceEpoch], Iterable[UniqueArrivalKey])]
}

case class DbManifestArrivalKeys(tables: AggregatedDbTables, destinationPortCode: PortCode)
                                (implicit ec: ExecutionContext) extends ManifestArrivalKeys {
  val log: Logger = LoggerFactory.getLogger(getClass)

  import tables.profile.api._

  override def nextKeys(since: Long): Future[(Option[MillisSinceEpoch], Iterable[UniqueArrivalKey])] = {
    val ts = SDate(since).toISOString.dropRight(1) + "." + SDate(since).millisSinceEpoch.toString.takeRight(3) + "Z"

    tables.run(manifestsQuery(ts))
      .map {
        case Some((arrivalKeys, nextTs)) => (Option(nextTs), arrivalKeys)
        case _ => (None, Vector())
      }
      .recover {
        case t =>
          log.error(s"Failed to get next keys from DB", t)
          (None, Vector())
      }
  }

  def manifestsQuery(ts: String): DBIOAction[Option[(Seq[UniqueArrivalKey], Long)], NoStream, Effect] =
    sql"""SELECT pj.departure_port_code, pj.voyage_number, EXTRACT(EPOCH FROM pj.scheduled) * 1000, MAX(EXTRACT(EPOCH FROM pz.processed_at)) * 1000
         |FROM processed_json pj
         |INNER JOIN processed_zip pz on pz.zip_file_name=pj.zip_file_name
         |WHERE
         |  pz.processed_at > TIMESTAMP '#$ts'
         |  AND pj.arrival_port_code = ${destinationPortCode.iata}
         |  AND event_code = 'DC'
         |GROUP BY pj.departure_port_code, pj.voyage_number, pj.scheduled
         |ORDER BY pj.scheduled
         |""".stripMargin
      .as[(String, Int, Long, Long)]
      .map { rows =>
        if (rows.nonEmpty) {
          val nextTs = rows.map(_._4).max
          val keys = rows.map {
            case (origin, voyageNumber, scheduled, _) =>
              UniqueArrivalKey(destinationPortCode, PortCode(origin), VoyageNumber(voyageNumber), SDate(scheduled, DateTimeZone.UTC))
          }
          Option(keys, nextTs)
        }
        else None
      }
}
