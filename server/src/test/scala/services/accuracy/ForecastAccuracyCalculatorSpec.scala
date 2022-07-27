package services.accuracy

import akka.testkit.TestProbe
import services.SDate
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class ForecastAccuracyCalculatorSpec extends CrunchTestLike {
  "Given a date of 01/01/2022 and the forecast at one day prior to calculate" >> {
    val date = LocalDate(2022, 1, 1)
    val daysToCalculate = List(1)
    val actualProbe = TestProbe("actual")
    val forecastProbe = TestProbe("forecast")
    val actualMock = (date: LocalDate) => {
      actualProbe.ref ! date
      Future.successful(Map[Terminal, Double]())
    }
    val forecastMock = (date: LocalDate, at: SDateLike) => {
      forecastProbe.ref ! (date, at)
      Future.successful(Map[Terminal, Double]())
    }

    "When I ask for the ForecastAccuracy and today is the day after the date being requested" >> {
      "I should see actualPaxNos being called with 01/01/2022, and forecastPaxNos with 01/01/2022 at midnight 31/12/2021" >> {
        ForecastAccuracyCalculator(date, daysToCalculate, actualMock, forecastMock, LocalDate(2022, 1, 2))
        actualProbe.expectMsg(date)
        forecastProbe.expectMsg((date, SDate(LocalDate(2021, 12, 31))))
        success
      }
    }

    "When I ask for the ForecastAccuracy and today is the same as the date being requested" >> {
      "I should not see any calls the to actual or forecast services" >> {
        ForecastAccuracyCalculator(date, daysToCalculate, actualMock, forecastMock, LocalDate(2022, 1, 1))
        actualProbe.expectNoMessage(100.milliseconds)
        forecastProbe.expectNoMessage(100.milliseconds)
        success
      }
    }
  }
}
