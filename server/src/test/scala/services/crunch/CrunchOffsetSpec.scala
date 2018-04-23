package services.crunch

import drt.shared.SDateLike
import services.SDate
import services.graphstages.Crunch


class CrunchOffsetSpec extends CrunchTestLike {

  "Given an offset of 60 minutes " +
    "When I ask for the crunch start for minute 2018-01-01T00:59 " +
    "Then I should get 2017-12-31T01:00" >> {
    val minuteInQuestion = SDate("2018-01-01T00:59")
    val result = CrunchSystem.crunchStartWithOffset(60)(minuteInQuestion)

    val expected = SDate("2017-12-31T01:00")

    result === expected
  }

  "Given an offset of 60 minutes " +
    "When I ask for the crunch start for minute 2018-01-01T01:00 " +
    "Then I should get 2018-01-01T01:00" >> {
    val minuteInQuestion = SDate("2018-01-01T01:00")
    val result = CrunchSystem.crunchStartWithOffset(60)(minuteInQuestion)

    val expected = SDate("2018-01-01T01:00")

    result === expected
  }

  "Given an offset of 60 minutes " +
    "When I ask for the crunch start for minute 2018-01-01T01:01 " +
    "Then I should get 2018-01-01T01:00" >> {
    val minuteInQuestion = SDate("2018-01-01T01:01")
    val result = CrunchSystem.crunchStartWithOffset(60)(minuteInQuestion)

    val expected = SDate("2018-01-01T01:00")

    result === expected
  }
}
