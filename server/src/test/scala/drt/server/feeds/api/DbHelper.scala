package drt.server.feeds.api

import slickdb.Tables
import uk.gov.homeoffice.drt.time.SDateLike

import java.sql.Timestamp
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt

object DbHelper {
  def addZipRecord(tables: Tables, zipName: String, createdAt: Timestamp): Future[Int] = {
    import  tables.profile.api._

    val row = tables.ProcessedZipRow(zipName, success = false, createdAt)

    Await.ready(tables.run(TableQuery[tables.ProcessedZip] += row), 1.second)
  }

  def addJsonRecord(tables: Tables, zipName: String, jsonName: String, createdAt: Timestamp): Future[Int] = {
    import  tables.profile.api._

    val row = tables.ProcessedJsonRow(zipName, jsonName, suspicious_date = false, success = true, createdAt)

    Await.ready(tables.run(TableQuery[tables.ProcessedJson] += row), 1.second)
  }

  def addPaxRecord(tables: Tables, arrivalPort: String, departurePort: String, voyageNumber: Int, scheduled: SDateLike, paxId: String, jsonFileName: String): Any = {
    import tables.profile.api._

    val scheduledTs = new Timestamp(scheduled.millisSinceEpoch)

    val row = tables.VoyageManifestPassengerInfoRow(
      event_code = "DC",
      arrival_port_code = arrivalPort,
      departure_port_code = departurePort,
      voyage_number = voyageNumber,
      carrier_code = "BA",
      scheduled_date = scheduledTs,
      day_of_week = 1,
      week_of_year = 24,
      document_type = "P",
      document_issuing_country_code = "GBR",
      eea_flag = "Y",
      age = 34,
      disembarkation_port_code = "LHR",
      in_transit_flag = "N",
      disembarkation_port_country_code = "GBR",
      nationality_country_code = "GBR",
      passenger_identifier = paxId,
      in_transit = false,
      json_file = jsonFileName)

    Await.ready(tables.run(TableQuery[tables.VoyageManifestPassengerInfo] += row), 1.second)
  }

}
