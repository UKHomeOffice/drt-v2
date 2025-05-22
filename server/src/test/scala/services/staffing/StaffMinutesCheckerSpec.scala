package services.staffing

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestProbe
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.ports.config.Lhr
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

class StaffMinutesCheckerSpec extends Specification {
  "Given a StaffMinutesChecker with LHR's config and max-forecast-days of 2" >> {
    "I should see TerminalUpdateRequests for every terminal for the last 2 days of the max forecast period" >> {
      implicit val system: ActorSystem = ActorSystem("test")
      val testProbe = TestProbe("staffing-update-requests-queue")

      val today = SDate("2023-01-08T10:00")
      val forecastMaxDays = 3
      val checker = StaffMinutesChecker(() => today, testProbe.ref, forecastMaxDays, Lhr.config)
      checker.calculateForecastStaffMinutes()

      val expected = for {
        day <- Seq(LocalDate(2023, 1, 9), LocalDate(2023, 1, 10))
        terminal <- Lhr.config.terminals
      } yield {
        TerminalUpdateRequest(terminal, day)
      }

      testProbe.receiveN(8).toSet === expected.toSet

      success
    }
  }
}
