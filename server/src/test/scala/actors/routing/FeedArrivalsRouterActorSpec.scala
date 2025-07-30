package actors.routing

import actors.routing.FeedArrivalsRouterActor.utcDateToLocalDates
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.time.{LocalDate, UtcDate}

class FeedArrivalsRouterActorSpec extends AnyWordSpec with Matchers {
  "utcDateToLocalDates" should {
    "Give correct results for UTC dates" in {
      val utcDate = UtcDate(2025, 10, 27)
      val localDates = utcDateToLocalDates(utcDate)
      localDates shouldEqual Set(LocalDate(2025, 10, 27))
    }
    "Give correct results for BST dates" in {
      val utcDate = UtcDate(2025, 10, 25)
      val localDates = utcDateToLocalDates(utcDate)
      localDates shouldEqual Set(LocalDate(2025, 10, 25), LocalDate(2025, 10, 26))
    }
    "Give correct results for clock change dates BST to UTC" in {
      val utcDate = UtcDate(2025, 10, 26)
      val localDates = utcDateToLocalDates(utcDate)
      localDates shouldEqual Set(LocalDate(2025, 10, 26))
    }
    "Give correct results for clock change dates UTC to BST" in {
      val utcDate = UtcDate(2025, 3, 30)
      val localDates = utcDateToLocalDates(utcDate)
      localDates shouldEqual Set(LocalDate(2025, 3, 30), LocalDate(2025, 3, 31))
    }
  }
}
