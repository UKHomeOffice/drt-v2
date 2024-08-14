package services.crunch.deskrecs

import akka.Done
import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.db.{StatusDaily, StatusDailyTable}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.testsystem.db.AggregateDbH2
import uk.gov.homeoffice.drt.time.LocalDate

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class DrtRunnableGraphSpec extends AnyWordSpec with Matchers with BeforeAndAfter {
  private val aggDb = AggregateDbH2
  import aggDb.profile.api._

  before {
    aggDb.dropAndCreateH2Tables()
  }

  "setUpdatedAt" should {
    "update the status daily table" in {
      val portCode = PortCode("LHR")
      val columnToUpdate = (statusDaily: StatusDailyTable) => statusDaily.staffUpdatedAt
      val setUpdated = (statusDaily: StatusDaily, updatedAt: Long) => statusDaily.copy(staffUpdatedAt = Some(updatedAt))

      val setUpdatedAt = DrtRunnableGraph.setUpdatedAt(portCode, aggDb, columnToUpdate, setUpdated)

      val terminal = T1
      val date = LocalDate(2021, 1, 1)
      val updatedAt = 1610617200000L

      val result = Await.result(
        setUpdatedAt(terminal, date, updatedAt).flatMap(_ => aggDb.run(aggDb.statusDaily.filter(s =>
          s.terminal === terminal.toString && s.port === portCode.iata && s.dateLocal === date.toISOString).result)),
        1.second,
      )

      result should ===(Done)
    }
  }
}
