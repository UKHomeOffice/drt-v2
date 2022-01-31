package drt.shared

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.time.MilliTimes

import scala.concurrent.duration.DurationInt

class MilliTimesSpec extends Specification {
  "I should get the correct number of milliseconds when I ask for" >> {
    "1 second in milliseconds" >> {
      MilliTimes.oneSecondMillis === 1000
    }
    "1 minute in milliseconds" >> {
      MilliTimes.oneMinuteMillis === 60000
    }
    "1 hour in milliseconds" >> {
      MilliTimes.oneHourMillis === 3600000
    }
    "1 second in milliseconds" >> {
      MilliTimes.oneDayMillis === 86400000
    }
  }
  "Given a duration" >> {
    "I should be able to get its milliseconds" >> {
      val oneSecond = 1.second

      oneSecond.toMillis === MilliTimes.oneSecondMillis
    }
  }

}
