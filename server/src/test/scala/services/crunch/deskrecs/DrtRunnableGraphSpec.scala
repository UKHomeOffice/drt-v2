package services.crunch.deskrecs

import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.db.{StatusDaily, StatusDailyRow, StatusDailyTable}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2}
import uk.gov.homeoffice.drt.testsystem.db.AggregateDbH2
import uk.gov.homeoffice.drt.time.LocalDate

import java.sql.Timestamp
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
    "Update only the requested port, terminal & date" in {
      val portTerminalDateUpdates = Seq(
        (PortCode("LHR"), T1, LocalDate(2021, 1, 1), 1L),
        (PortCode("LHR"), T2, LocalDate(2021, 1, 2), 2L),
        (PortCode("LGW"), T1, LocalDate(2021, 1, 1), 3L),
      )
      val columnToUpdate = (statusDaily: StatusDailyTable) => statusDaily.staffUpdatedAt
      val setUpdated = (statusDaily: StatusDaily, updatedAt: Long) => statusDaily.copy(staffUpdatedAt = Some(updatedAt))

      portTerminalDateUpdates.foreach {
        case (portCode, terminal, date, updatedAt) =>
          val setUpdatedAt = DrtRunnableGraph.setUpdatedAt(portCode, aggDb, columnToUpdate, setUpdated)
          Await.result(setUpdatedAt(terminal, date, updatedAt), 1.second)
      }

      portTerminalDateUpdates.foreach {
        case (portCode, terminal, date, updatedAt) =>
          val result = Await.result(
            aggDb.run(aggDb.statusDaily.filter(
              s => s.terminal === terminal.toString && s.port === portCode.iata && s.dateLocal === date.toISOString).result),
            1.second,
          )
          result should ===(Vector(
            StatusDailyRow(portCode.iata, terminal.toString, date.toISOString, None, None, None, Some(new Timestamp(updatedAt))),
          ))
      }
    }
  }
}
