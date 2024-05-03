package drt.server.feeds.api

import akka.Done
import akka.testkit.TestProbe
import drt.server.feeds.api.DbHelper.addPaxRecord
import drt.server.feeds.{DqManifests, ManifestsFeedResponse, ManifestsFeedSuccess}
import manifests.UniqueArrivalKey
import org.specs2.specification.BeforeEach
import services.crunch.{CrunchTestLike, H2Tables}
import slick.jdbc.SQLActionBuilder
import slick.jdbc.SetParameter.SetUnit
import uk.gov.homeoffice.drt.arrivals.VoyageNumber
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.Try

class DbManifestProcessorTest
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

  "A manifest processor" should {
    val arrivalPort = "LHR"
    val departurePort = "JFK"
    val voyageNumber = 1234
    val scheduled = SDate("2022-06-01T12:00")
    val paxId = "1"
    val key = UniqueArrivalKey(PortCode(arrivalPort), PortCode(departurePort), VoyageNumber(voyageNumber), scheduled)

    "Find matching passengers and enqueue a successful manifest response for iAPI" in {
      implicit val probe: TestProbe = TestProbe("manifestProbe")
      addPaxRecord(H2Tables, arrivalPort, departurePort, voyageNumber, scheduled, paxId, "a.json")

      processAndCheckIapiManifestPax(key, Set(paxId))

      success
    }

    "Find matching passengers and enqueue a successful manifest response, using only unique passenger identifiers for iAPI" in {
      implicit val probe: TestProbe = TestProbe("manifestProbe")
      addPaxRecord(H2Tables, arrivalPort, departurePort, voyageNumber, scheduled, paxId, "a.json")
      addPaxRecord(H2Tables, arrivalPort, departurePort, voyageNumber, scheduled, paxId, "a.json")

      processAndCheckIapiManifestPax(key, Set(paxId))

      success
    }

    "Find matching passengers and enqueue a successful manifest response, using only unique passenger identifiers for iAPI from multiple json files" in {
      implicit val probe: TestProbe = TestProbe("manifestProbe")
      val paxId1 = "1"
      val paxId2 = "2"
      val paxId3 = "3"

      addPaxRecord(H2Tables, arrivalPort, departurePort, voyageNumber, scheduled, paxId1, "a.json")
      addPaxRecord(H2Tables, arrivalPort, departurePort, voyageNumber, scheduled, paxId2, "a.json")
      addPaxRecord(H2Tables, arrivalPort, departurePort, voyageNumber, scheduled, paxId2, "b.json")
      addPaxRecord(H2Tables, arrivalPort, departurePort, voyageNumber, scheduled, paxId3, "b.json")

      processAndCheckIapiManifestPax(key, Set(paxId1, paxId2, paxId3))

      success
    }

    "Find matching passengers and enqueue a successful manifest response, using only unique passenger identifiers for non-iAPI" in {
      implicit val probe: TestProbe = TestProbe("manifestProbe")
      addPaxRecord(H2Tables, arrivalPort, departurePort, voyageNumber, scheduled, "", "a.json")
      addPaxRecord(H2Tables, arrivalPort, departurePort, voyageNumber, scheduled, "", "a.json")

      processAndCheckNonIapiManifestPax(key, 2)

      success
    }
  }

  private def processAndCheckIapiManifestPax(key: UniqueArrivalKey, expectedPaxIds: Set[String])
                                            (implicit probe: TestProbe): Any = {
    processor(probe).process(Seq(key), SDate.now().millisSinceEpoch)

    probe.fishForMessage(1.second) {
      case ManifestsFeedSuccess(DqManifests(_, manifests), _) =>
        val pax = manifests.head.uniquePassengers.flatMap(_.passengerIdentifier)
        pax.size == expectedPaxIds.size && pax.toSet == expectedPaxIds
    }
  }

  private def processAndCheckNonIapiManifestPax(key: UniqueArrivalKey, expectedPaxCount: Int)
                                               (implicit probe: TestProbe): Any = {
    processor(probe).process(Seq(key), SDate.now().millisSinceEpoch)

    probe.fishForMessage(1.second) {
      case ManifestsFeedSuccess(DqManifests(_, manifests), _) =>
        manifests.head.uniquePassengers.size == expectedPaxCount
    }
  }

  private def processor(probe: TestProbe) = {
//    val manifestQueue = Source
//      .queue[ManifestsFeedResponse](10, OverflowStrategy.dropHead)
//      .map(probe.ref ! _).toMat(Sink.ignore)(Keep.left).run()
    val handleManifestResponse: ManifestsFeedResponse => Future[Done] =
      response => {
        probe.ref ! response
        Future(Done)
      }

    DbManifestProcessor(H2Tables, PortCode("LHR"), handleManifestResponse)
  }
}
