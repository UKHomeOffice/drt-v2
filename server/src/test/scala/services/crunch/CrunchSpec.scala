package services.crunch

import drt.shared.CrunchApi.MillisSinceEpoch
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import services.SDate
import services.graphstages.Crunch
import services.graphstages.Crunch.crunchStartWithOffset
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.MilliTimes.{minutesInADay, oneDayMillis, oneHourMillis, oneMinuteMillis}

class CrunchSpec extends Specification {
  "When I ask for minuteInADay " +
    "Then I should see 60 * 24 (1440)" >> {
    minutesInADay === 60 * 24
  }

  "When I ask for oneMinuteMillis " +
    "Then I should see 60 * 1000" >> {
    oneMinuteMillis === 60 * 1000
  }

  "When I ask for oneHourMillis " +
    "Then I should see 60 * 60 * 1000" >> {
    oneHourMillis === 60 * 60 * 1000
  }

  "When I ask for oneDayMillis " +
    "Then I should see 60 * 60 * 24 * 1000" >> {
    oneDayMillis === 60 * 60 * 24 * 1000
  }

  "Given 2019-01-01T02:00 with a crunch offset of 120 minutes " +
    "The crunch start should be 2019-01-01T02:00" >> {
    val timeInQuestion = SDate("2019-01-01T02:00")
    val crunchStart = crunchStartWithOffset(120)(timeInQuestion)

    val expected = SDate("2019-01-01T02:00")

    crunchStart.millisSinceEpoch === expected.millisSinceEpoch
  }

  "Given 2019-01-01T01:59 with a crunch offset of 120 minutes " +
    "The crunch start should be 2018-12-31T02:00" >> {
    val timeInQuestion = SDate("2019-01-01T01:59")
    val crunchStart = crunchStartWithOffset(120)(timeInQuestion)

    val expected = SDate("2018-12-31T02:00")

    crunchStart.toISOString === expected.toISOString
  }

  "Given 2019-01-01T02:01:33 with a crunch offset of 120 minutes " +
    "The crunch start should be 2018-12-31T02:00" >> {
    val timeInQuestion = SDate("2019-01-01T02:01")
    val crunchStart = crunchStartWithOffset(120)(timeInQuestion)

    val expected = SDate("2019-01-01T02:00")

    crunchStart.toISOString === expected.toISOString
  }

  "Given a now of 2020-06-01T00:00 BST" >> {
    val now = SDate("2020-06-01T12:00", Crunch.europeLondonTimeZone)
    "When I ask isHistoric for the same date" >> {
      "Then I should get false" >> {
        val isHistoric = now.isHistoricDate(now)
        isHistoric === false
      }
    }

    "When I ask isHistoric for BST midnight earlier that day" >> {
      val date = SDate("2020-06-01T00:00", Crunch.europeLondonTimeZone)
      "Then I should get false" >> {
        val isHistoric = date.isHistoricDate(now)
        isHistoric === false
      }
    }

    "When I ask isHistoric for 1 minute before BST midnight earlier that day" >> {
      val date = SDate("2020-06-01T00:00", Crunch.europeLondonTimeZone).addMinutes(-1)
      "Then I should get true" >> {
        val isHistoric = date.isHistoricDate(now)
        isHistoric === true
      }
    }
  }

  "Given a now of 2020-06-01T12:00 BST" >> {
    val now = SDate("2020-06-01T12:00", Crunch.europeLondonTimeZone)
    "When I ask isHistoric for the same date" >> {
      "Then I should get false" >> {
        val isHistoric = now.isHistoricDate(now)
        isHistoric === false
      }
    }

    "When I ask isHistoric for BST midnight earlier that day" >> {
      val date = SDate("2020-06-01T00:00", Crunch.europeLondonTimeZone)
      "Then I should get false" >> {
        val isHistoric = date.isHistoricDate(now)
        isHistoric === false
      }
    }

    "When I ask isHistoric for 1 minute before BST midnight earlier that day" >> {
      val date = SDate("2020-06-01T00:00", Crunch.europeLondonTimeZone).addMinutes(-1)
      "Then I should get true" >> {
        val isHistoric = date.isHistoricDate(now)
        isHistoric === true
      }
    }
  }
}
