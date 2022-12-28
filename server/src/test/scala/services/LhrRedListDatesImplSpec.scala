package services

import drt.shared.redlist.LhrRedListDatesImpl
import org.specs2.mutable.Specification
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.time.SDate

class LhrRedListDatesImplSpec extends Specification {
  "A LhrRedListDatesImpl should" >> {
    "Give the T3 opening date as 2021-06-01 in BST" >> {
      LhrRedListDatesImpl.t3RedListOpeningDate === SDate("2021-06-01T00:00", Crunch.europeLondonTimeZone).millisSinceEpoch
    }
    "Give the T4 opening date as 2021-06-29 in BST" >> {
      LhrRedListDatesImpl.t4RedListOpeningDate === SDate("2021-06-29T00:00", Crunch.europeLondonTimeZone).millisSinceEpoch
    }
  }
}
