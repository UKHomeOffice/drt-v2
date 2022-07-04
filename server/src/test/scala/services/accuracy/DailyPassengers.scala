package services.accuracy

import services.SDate
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}


class DailyPassengersSpec extends CrunchTestLike {
  "Given forecast and actual numbers of passengers and I ask for the accuracy stat" >> {
    "When forecast is 9,000 and actual is 10,000" >> {
      val forecast = 9000
      val actual = 10000
      "Then I should get 90%" >> {
        accuracy(forecast, actual) === 90
      }
    }
    "When forecast is 11,000 and actual is 10,000" >> {
      val forecast = 11000
      val actual = 10000
      "Then I should get 90%" >> {
        accuracy(forecast, actual) === 90
      }
    }
    "When forecast is 11,000 and actual is 0" >> {
      val forecast = 11000
      val actual = 0
      "Then I should get 0%" >> {
        accuracy(forecast, actual) === 0
      }
    }
    "When forecast is 0 and actual is 10,000" >> {
      val forecast = 0
      val actual = 10000
      "Then I should get 0%" >> {
        accuracy(forecast, actual) === 0
      }
    }
  }

  private def accuracy(forecast: Int, actual: Int): Double = {
    val acc = Accuracy((_, _) => Future.successful(forecast), _ => Future.successful(actual))
    Await.result(acc.accuracy(LocalDate(2022, 7, 4), SDate("2022-07-04")), 1.second)
  }
}

case class Accuracy(forecast: (LocalDate, SDateLike) => Future[Double], actual: LocalDate => Future[Double])
                   (implicit ec: ExecutionContext) {
  def accuracy(date: LocalDate, forecastAt: SDateLike): Future[Double] = {
    for {
      fVal <- forecast(date, forecastAt)
      aVal <- actual(date)
    } yield accuracyPercentage(fVal, aVal)
  }

  def accuracyPercentage(forecast: Double, actual: Double): Double = {
    if (actual == 0 && forecast != 0)
      0
    else
      (1 - (Math.abs(forecast - actual) / actual)) * 100
  }

}
