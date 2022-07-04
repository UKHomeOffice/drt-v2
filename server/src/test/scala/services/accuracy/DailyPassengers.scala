package services.accuracy

import org.specs2.mutable.Specification


class DailyPassengersSpec extends Specification {
  "Given forecast and actual numbers of passengers and I ask for the accuracy stat" >> {
    "When forecast is 9,000 and actual is 10,000" >> {
      val forecast = 9000
      val actual = 10000
      "Then I should get 90%" >> {
        accuracyPencentage(forecast, actual) === 90
      }
    }
    "When forecast is 11,000 and actual is 10,000" >> {
      val forecast = 11000
      val actual = 10000
      "Then I should get 90%" >> {
        accuracyPencentage(forecast, actual) === 90
      }
    }
    "When forecast is 11,000 and actual is 0" >> {
      val forecast = 11000
      val actual = 0
      "Then I should get 0%" >> {
        accuracyPencentage(forecast, actual) === 0
      }
    }
    "When forecast is 0 and actual is 10,000" >> {
      val forecast = 0
      val actual = 10000
      "Then I should get 0%" >> {
        accuracyPencentage(forecast, actual) === 0
      }
    }
  }

  private def accuracyPencentage(forecast: Int, actual: Int): Double = {
    if (actual == 0 && forecast != 0)
      0
    else
      (1 - (Math.abs(forecast - actual).toDouble / actual)) * 100
  }
}
