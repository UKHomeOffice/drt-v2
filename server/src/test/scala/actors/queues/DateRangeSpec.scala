package actors.queues

import actors.DateRange
import org.specs2.mutable.Specification
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.time.{DateLike, LocalDate, UtcDate}

class DateRangeSpec extends Specification {
  "Concerning BST dates" >> {
    "Given a start date of 2020-05-01T00:00+1 (2020-04-30T23:00) and an end date the same" >> {
      "When I ask for the UTC date range" >> {
        "I should get 2020-04-30" >> {
          val date = SDate("2020-05-01T00:00:00+01:00")
          val range: Seq[DateLike] = DateRange.utcDateRange(date, date)

          range === Seq(UtcDate(2020, 4, 30))
        }
      }
    }

    "Given a start date of 2020-05-01T00:00+1 (2020-04-30T23:00) and an end date the same" >> {
      "When I ask for the Local date range" >> {
        "I should get 2020-05-01" >> {
          val date = SDate("2020-05-01T00:00:00+01:00")
          val range: Seq[DateLike] = DateRange.localDateRange(date, date)

          range === Seq(LocalDate(2020, 5, 1))
        }
      }
    }

    "Given a start and end date that span two UTC dates but one BST date" >> {
      "When I ask for a UTC Date range" >> {
        "I should get back both UTC Dates in the range" >> {
          val date1 = SDate("2020-04-02T00:00", Crunch.europeLondonTimeZone)
          val date2 = SDate("2020-04-02T02:00", Crunch.europeLondonTimeZone)
          val range: Seq[DateLike] = DateRange.utcDateRange(date1, date2)

          range === Seq(UtcDate(2020, 4, 1), UtcDate(2020, 4, 2))
        }
      }
    }
  }

  "Concerning UTC dates" >> {
    "Given a start date of 2020-01-01T00:00 and an end date the same" >> {
      "When I ask for the local date range" >> {
        "I should get just 2020-01-01" >> {
          val date = SDate("2020-01-01T00:00:00")
          val range: Seq[DateLike] = DateRange.utcDateRange(date, date)

          range === Seq(UtcDate(2020, 1, 1))
        }
      }
    }

    "Given a start date of 2020-05-01T00:00 and an end date the same" >> {
      "When I ask for the local date range" >> {
        "I should get just 2020-01-01" >> {
          val date = SDate("2020-01-01T00:00")
          val range: Seq[DateLike] = DateRange.localDateRange(date, date)

          range === Seq(LocalDate(2020, 1, 1))
        }
      }
    }
  }

  "Given a date range that spans two dates but less than 24 hours" >> {
    "When I ask for the local date range" >> {
      "I should get 2 dates back in the range" >> {

        val date1 = SDate("2020-01-01T12:00")
        val date2 = SDate("2020-01-02T10:00")
        val range: Seq[DateLike] = DateRange.localDateRange(date1, date2)

        range === Seq(LocalDate(2020, 1, 1), LocalDate(2020, 1, 2))

      }
    }
  }

  "Given start & end LocalDates" >> {
    "I should get an inclusive range of LocalDates" >> {
      DateRange(LocalDate(2020, 1, 30), LocalDate(2020, 2, 2)) === Seq(
        LocalDate(2020, 1, 30),
        LocalDate(2020, 1, 31),
        LocalDate(2020, 2, 1),
        LocalDate(2020, 2, 2),
      )
    }
  }
}
