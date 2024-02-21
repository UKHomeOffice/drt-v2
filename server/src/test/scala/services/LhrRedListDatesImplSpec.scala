package services

import drt.shared.redlist.LhrRedListDatesImpl
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone

class LhrRedListDatesImplSpec extends Specification {
  "A LhrRedListDatesImpl should" >> {
    "Give the T3 opening date as 2021-06-01 in BST" >> {
      LhrRedListDatesImpl.t3RedListOpeningDate === SDate("2021-06-01T00:00", europeLondonTimeZone).millisSinceEpoch
    }
    "Give the T4 opening date as 2021-06-29 in BST" >> {
      LhrRedListDatesImpl.t4RedListOpeningDate === SDate("2021-06-29T00:00", europeLondonTimeZone).millisSinceEpoch
    }
    "Give the Red list start date as 2021-02-21 in BST" >> {
      LhrRedListDatesImpl.startRedListingDate === SDate("2021-02-15T00:00", europeLondonTimeZone).millisSinceEpoch
    }
    "Give the Red list end date as 2021-12-21 in BST" >> {
      LhrRedListDatesImpl.endRedListingDate === SDate("2021-12-15T00:00", europeLondonTimeZone).millisSinceEpoch
    }
  }

  "A LhrRedListDatesImpl.dayHasPaxDiversions should" >> {
    "Give a date it should be red listed as true if it is after the start date and before the end date" >> {
      LhrRedListDatesImpl.isRedListActive(SDate("2021-06-01T00:00", europeLondonTimeZone)) === true
    }
    "Give a date it should be red listed as false if it is before the start date" >> {
      LhrRedListDatesImpl.isRedListActive(SDate("2021-02-14T00:00", europeLondonTimeZone)) === false
    }
    "Give a date it should be red listed as false if it is after the end date" >> {
      LhrRedListDatesImpl.isRedListActive(SDate("2021-12-16T00:00", europeLondonTimeZone)) === false
    }
  }

  "LhrRedListDatesImpl.overlapsRedListDates should" >> {
    "return true given a date range starting before and ending after the red list period" >> {
      LhrRedListDatesImpl.overlapsRedListDates(SDate("2021-01-01T00:00", europeLondonTimeZone),
        SDate("2022-01-15T00:00", europeLondonTimeZone)) === true
    }

    "return false given a date range falling ending before the red list period starts" >> {
      LhrRedListDatesImpl.overlapsRedListDates(SDate("2021-01-01T00:00", europeLondonTimeZone),
        SDate("2021-02-14T00:00", europeLondonTimeZone)) === false
    }

    "return false given a date range falling starting after the red list period ends" >> {
      LhrRedListDatesImpl.overlapsRedListDates(SDate("2021-12-16T00:00", europeLondonTimeZone),
        SDate("2022-01-15T00:00", europeLondonTimeZone)) === false
    }

    "return true when given a date falling inside the red list period" >> {
      LhrRedListDatesImpl.overlapsRedListDates(SDate("2021-05-01T00:00", europeLondonTimeZone),
        SDate("2021-06-14T00:00", europeLondonTimeZone)) === true
    }

    "return true given a date range overlapping the start of the red list period" >> {
      LhrRedListDatesImpl.overlapsRedListDates(SDate("2021-01-01T00:00", europeLondonTimeZone),
        SDate("2021-02-24T00:00", europeLondonTimeZone)) === true
    }

    "return true given a date range overlapping the end of the red list period" >> {
      LhrRedListDatesImpl.overlapsRedListDates(SDate("2021-10-01T00:00", europeLondonTimeZone),
        SDate("2022-02-24T00:00", europeLondonTimeZone)) === true
    }
  }

}
