package services

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.time.LocalDate

class ServerLocalDateSpec extends Specification {

  "Given a valid date string when parse the date I should get back an option of LocalDate" >> {
    val validDateString = "2020-03-01"
    val result = LocalDate.parse(validDateString)

    result === Some(LocalDate(2020,3,1))
  }

  "Given an invalid date string when parse the date I should get back None" >> {
    val validDateString = "2020-03"
    val result = LocalDate.parse(validDateString)

    result === None
  }

  "Given a string containing dashes but no numbers then I should get back None" >> {
    val validDateString = "year-month-day"
    val result = LocalDate.parse(validDateString)

    result === None
  }

}
