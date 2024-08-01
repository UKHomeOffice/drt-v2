package slickdb

import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import slickdb.dao.AggregatedDao
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.testsystem.db.AggregateDbH2
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import java.sql.Timestamp
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class AggregatedDaoSpec extends AnyWordSpec with Matchers with BeforeAndAfter {
  private val aggDb = AggregateDbH2
  import aggDb.profile.api._

  before {

    aggDb.dropAndCreateH2Tables()
    val inserts = for {
      destination <- Seq("LHR", "LGW", "STN")
      scheduled <- Seq(SDate("2021-01-01T00:00"), SDate("2021-01-02T00:00"), SDate("2021-01-03T00:00"), SDate("2021-01-04T00:00"))
    } yield aggDb.run(aggDb.arrival += arrival(scheduled, destination))
    Await.ready(Future.sequence(inserts), 1.second)
  }

  "deleteArrivalsBefore" should {
    "delete all arrivals for a destination before the given date" in {
      val dao = AggregatedDao(aggDb, () => SDate("2021-01-03"), PortCode("LHR"))

      Await.result(dao.deleteArrivalsBefore(SDate("2021-01-02").toUtcDate), 1.second)

      val lhrArrivals = Await.result(aggDb.run(aggDb.arrival.filter(_.destination === "LHR").result), 1.second)
      val stnArrivals = Await.result(aggDb.run(aggDb.arrival.filter(_.destination === "STN").result), 1.second)
      val lgwArrivals = Await.result(aggDb.run(aggDb.arrival.filter(_.destination === "LGW").result), 1.second)

      lhrArrivals.map(_.scheduled) should contain only (new Timestamp(SDate("2021-01-02T00:00").millisSinceEpoch), new Timestamp(SDate("2021-01-03T00:00").millisSinceEpoch), new Timestamp(SDate("2021-01-04T00:00").millisSinceEpoch))
      stnArrivals should have size 4
      lgwArrivals should have size 4
    }
  }

  def arrival(scheduled: SDateLike, destination: String): ArrivalRow = {
    ArrivalRow(
      code = "BA0001",
      number = 1,
      destination = destination,
      origin = "JFK",
      terminal = "T1",
      gate = Option("G1"),
      stand = Option("S1"),
      status = "On Chocks",
      scheduled = new Timestamp(scheduled.millisSinceEpoch),
      estimated = Option(new Timestamp(scheduled.addMinutes(5).millisSinceEpoch)),
      actual = Option(new Timestamp(scheduled.addMinutes(6).millisSinceEpoch)),
      estimatedchox = Option(new Timestamp(scheduled.addMinutes(7).millisSinceEpoch)),
      actualchox = Option(new Timestamp(scheduled.addMinutes(8).millisSinceEpoch)),
      pcp = new Timestamp(scheduled.addMinutes(10).millisSinceEpoch),
      totalpassengers = Option(100),
      pcppassengers = Option(195),
      scheduled_departure = Option(new Timestamp(scheduled.addHours(-5).millisSinceEpoch)),
    )
  }

}
