package drt.client.components

import uk.gov.homeoffice.drt.models.AgeRange
import uk.gov.homeoffice.drt.time.LocalDate
import utest._

object FlightTableAgeGroupsTest extends TestSuite {
  private val staticAgeGroups = Seq(
    AgeRange(18, 24).title,
    AgeRange(25, 49).title,
    AgeRange(50, 65).title,
    AgeRange(65, None).title
  )

  def tests: Tests = Tests {
    test("uses pre-change age groups before eligibility date") {
      val result = FlightTable.ageGroupsForViewDate(LocalDate(2026, 7, 7)).toSeq

      assert(result == Seq(AgeRange(0, 9).title, AgeRange(10, 17).title) ++ staticAgeGroups)
    }

    test("uses post-change age groups on eligibility date") {
      val result = FlightTable.ageGroupsForViewDate(LocalDate(2026, 7, 8)).toSeq

      assert(result == Seq(AgeRange(0, 7).title, AgeRange(8, 17).title) ++ staticAgeGroups)
    }

    test("uses post-change age groups after eligibility date") {
      val result = FlightTable.ageGroupsForViewDate(LocalDate(2026, 7, 9)).toSeq

      assert(result == Seq(AgeRange(0, 7).title, AgeRange(8, 17).title) ++ staticAgeGroups)
    }
  }
}

