package drt.server.feeds.api

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import manifests.UniqueArrivalKey
import org.specs2.mutable.BeforeAfter
import server.feeds.ManifestsFeedResponse
import services.SDate
import services.crunch.{CrunchTestLike, InMemoryConnection}
import slickdb.{DbConnection, Tables}
import uk.gov.homeoffice.drt.arrivals.VoyageNumber
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.SDateLike

import java.sql.Timestamp
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class DbManifestProcessorTest
  extends CrunchTestLike
  with BeforeAfter {

  override def before: Any = {
    import InMemoryConnection.profile.api._
    val schema = Tables(InMemoryConnection).schema
    val statements = schema.createStatements
    statements.map {
      stmt => Await.ready(InMemoryConnection.db.run(sqlu"""$stmt"""), 1.second)
    }
  }

  override def after: Unit = {}

  val tables: Tables = Tables(InMemoryConnection)
  import tables.dbConnection.profile.api._

  "A manifest processor" should {
    "Find matching passengers and enqueue a successful manifest response" in {
      val probe = TestProbe("manifestprobe")
      val arrivalPort = "LHR"
      val departurePort = "JFK"
      val voyageNumber = 1234
      val scheduled = SDate("2022-06-01T12:00")
      val key = UniqueArrivalKey(PortCode(arrivalPort), PortCode(departurePort), VoyageNumber(voyageNumber), scheduled)
      addPaxRecord(tables, arrivalPort, departurePort, voyageNumber, scheduled, "1")
      val manifestQueue = Source
        .queue[ManifestsFeedResponse](1, OverflowStrategy.dropHead)
        .map { x =>
          println(s"Got $x")
          probe.ref ! x
        }
        .to(Sink.ignore)
        .run()
      val dbManifestProcessor = DbManifestProcessor(tables, PortCode("LHR"), manifestQueue)
      dbManifestProcessor.process(key, SDate.now().millisSinceEpoch)
      probe.expectMsg(true)
    }
  }

  private def addPaxRecord(tables: Tables, arrivalPort: String, departurePort: String, voyageNumber: Int, scheduled: SDateLike, paxId: String): Any = {

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
      json_file = "a.json")

    val tableQuery = TableQuery[tables.VoyageManifestPassengerInfo]
    Await.ready(tables.dbConnection.db.run(tableQuery += row), 1.second)
  }
}
