package drt.server.feeds.api

import drt.server.feeds.api.DbHelper.{addJsonRecord, addPaxRecord, addZipRecord}
import drt.shared.CrunchApi.MillisSinceEpoch
import manifests.UniqueArrivalKey
import org.specs2.specification.BeforeEach
import uk.gov.homeoffice.drt.time.SDate
import services.crunch.{CrunchTestLike, H2Tables}
import slick.jdbc.SQLActionBuilder
import slick.jdbc.SetParameter.SetUnit
import uk.gov.homeoffice.drt.arrivals.VoyageNumber
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.SDateLike

import java.sql.Timestamp
import scala.collection.immutable.List
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Try

class DbManifestArrivalKeysTest
  extends CrunchTestLike with BeforeEach {

  override def before: Any = {
    clearDatabase()
  }

  def clearDatabase(): Unit = {
    Try(dropTables())
    createTables()
  }

  def createTables(): Unit = {
    H2Tables.schema.createStatements.toList.foreach { query =>
      Await.result(H2Tables.db.run(SQLActionBuilder(List(query), SetUnit).asUpdate), 1.second)
    }
  }

  def dropTables(): Unit = {
    H2Tables.schema.dropStatements.toList.reverse.foreach { query =>
      Await.result(H2Tables.db.run(SQLActionBuilder(List(query), SetUnit).asUpdate), 1.second)
    }
  }

  "A DbManifestArrivalKeys" should {
    val lhr = "LHR"
    val jfk = "JFK"

    "Return nothing when there are no voyage manifest rows" in {
      val nextKeys = Await.result(DbManifestArrivalKeys(H2Tables, PortCode(lhr)).nextKeys(0L), 1.second)

      nextKeys === Iterable()
    }

    "Return nothing when there are no zip files with a later processed-at date" in {
      val date = SDate("2022-06-01T12:00")
      val processedAt = new Timestamp(date.millisSinceEpoch)

      createProcessedPassengerJsonZip("1.zip", "1.json", lhr, jfk, date, voyageNumber = 1, paxCount = 5, processedAt)
      val nextKeys = Await.result(DbManifestArrivalKeys(H2Tables, PortCode(lhr)).nextKeys(processedAt.getTime), 1.second)

      nextKeys === Iterable()
    }

    "Return a later arrival's key when there is a zip, json and pax records with later processed-at dates, but not earlier records" in {
      val date = SDate("2022-06-01T12:00")
      val processedAt = new Timestamp(date.millisSinceEpoch)
      val laterProcessedAt = new Timestamp(date.addMinutes(1).millisSinceEpoch)

      createProcessedPassengerJsonZip("1.zip", "1.json", lhr, jfk, date, voyageNumber = 1, paxCount = 5, processedAt)
      createProcessedPassengerJsonZip("2.zip", "2.json", lhr, jfk, date, voyageNumber = 99, paxCount = 5, laterProcessedAt)

      val nextKeys: Iterable[(UniqueArrivalKey, MillisSinceEpoch)] = Await.result(DbManifestArrivalKeys(H2Tables, PortCode(lhr)).nextKeys(processedAt.getTime), 1.second)

      nextKeys === Iterable((UniqueArrivalKey(PortCode(lhr), PortCode(jfk), VoyageNumber(99), date), laterProcessedAt.getTime))
    }
  }

  private def createProcessedPassengerJsonZip(zipName: String, jsonName: String, arrivalPort: String, departurePort: String, scheduled: SDateLike, voyageNumber: Int, paxCount: Int, processedAt: Timestamp) = {
    addZipRecord(H2Tables, zipName, processedAt)
    addJsonRecord(H2Tables, zipName, jsonName, processedAt)

    (0 until paxCount).foreach (id => addPaxRecord(H2Tables, arrivalPort, departurePort, voyageNumber, scheduled, id.toString, jsonName))
  }
}
