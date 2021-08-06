package drt.shared.redlist

import drt.shared.SDateLike
import org.specs2.mutable.Specification
import services.SDate
import services.graphstages.Crunch

class RedListSpec extends Specification{
  val date20210807: SDateLike = SDate("2021-08-07T00:00", Crunch.europeLondonTimeZone)
  val date20210808: SDateLike = SDate("2021-08-08T00:00", Crunch.europeLondonTimeZone)

  "Given a date just before the red list changes on 08/08/2021" >> {
    val redList20210807 = RedList.countryCodesByName(date20210807.millisSinceEpoch)
    "The red list should contain India" >> {
      redList20210807.contains("India") === true
    }
  }

  "Given a date just before the red list changes on 08/08/2021" >> {
    val redList20210808 = RedList.countryCodesByName(date20210808.millisSinceEpoch)
    "The red list should not contain India" >> {
      redList20210808.contains("India") === false
    }
  }
}
