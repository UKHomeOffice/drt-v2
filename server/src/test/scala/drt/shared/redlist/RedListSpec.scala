package drt.shared.redlist

import drt.shared.SDateLike
import org.specs2.mutable.Specification
import services.SDate
import services.graphstages.Crunch

class RedListSpec extends Specification{
  val date20210807: SDateLike = SDate("2021-08-07T00:00", Crunch.europeLondonTimeZone)
  val date20210808: SDateLike = SDate("2021-08-08T00:00", Crunch.europeLondonTimeZone)
  val countriesRemovedFrom20210808 = Set("Bahrain", "India", "Qatar", "United Arab Emirates")
  val countriesAddedFrom20210808 = Set("Georgia", "Mayotte", "Mexico", "Reunion")

  "Given a date just before the red list changes on 08/08/2021" >> {
    val redList20210807 = RedList.countryCodesByName(date20210807.millisSinceEpoch)
    "The red list should contain India" >> {
      val expectedToExist = redList20210807.keys.toSet.intersect(countriesRemovedFrom20210808)
      val expectedToNotExist = redList20210807.keys.toSet.intersect(countriesAddedFrom20210808)

      expectedToExist === countriesRemovedFrom20210808 && expectedToNotExist === Set()
    }
  }

  "Given a date just before the red list changes on 08/08/2021" >> {
    val redList20210808 = RedList.countryCodesByName(date20210808.millisSinceEpoch)
    "The red list should not contain India" >> {
      val expectedToNotExist = redList20210808.keys.toSet.intersect(countriesRemovedFrom20210808)
      val expectedToExist = redList20210808.keys.toSet.intersect(countriesAddedFrom20210808)

      expectedToNotExist === Set() && expectedToExist == countriesAddedFrom20210808
    }
  }
}
