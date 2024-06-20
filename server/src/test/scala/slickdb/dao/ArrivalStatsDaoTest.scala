package slickdb.dao

import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import slickdb.ArrivalStatsRow
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.testsystem.db.AggregateDbH2
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class ArrivalStatsDaoTest extends AnyWordSpec with Matchers with BeforeAndAfter {
  private val aggDb = AggregateDbH2

  before {
    aggDb.dropAndCreateH2Tables()
  }

  "addOrUpdate" should {
    "Insert a row and get should return it" in {
      val arrivalStatsDao = ArrivalStatsDao(aggDb, () => SDate(2020, 1, 1), PortCode("LHR"))

      val row = ArrivalStatsRow(
        portCode = "LHR",
        terminal = "T1",
        date = "2020-01-01",
        dataType = "forecast",
        daysAhead = 1,
        flights = 10,
        capacity = 1000,
        pax = 2000,
        averageLoad = 95.0,
        createdAt = SDate(2020, 1, 1).millisSinceEpoch,
      )

      Await.result(arrivalStatsDao.addOrUpdate(row), 1.second) should be(1)

      val maybeRow = Await.result(arrivalStatsDao.get("T1", "2020-01-01", 1, "forecast"), 1.second)

      maybeRow should be(Some(row))
    }
  }
}
