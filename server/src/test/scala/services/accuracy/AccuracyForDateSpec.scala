package services.accuracy

import services.AccuracyForDate
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.time.LocalDate

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}


class AccuracyForDateSpec extends CrunchTestLike {
  "Given forecast and actual numbers of passengers and I ask for the accuracy stat" >> {
    "When forecast is 9,000 and actual is 10,000" >> {
      val forecast = 9000
      val actual = 10000
      "Then I should get -10%" >> {
        accuracy(forecast, actual) === Map(T1 -> Option(-10))
      }
    }
    "When forecast is 11,000 and actual is 10,000" >> {
      val forecast = 11000
      val actual = 10000
      "Then I should get 10%" >> {
        accuracy(forecast, actual).mapValues(_.map(_.toInt)) === Map(T1 -> Option(10))
      }
    }
    "When forecast is 11,000 and actual is 0" >> {
      val forecast = 11000
      val actual = 0
      "Then I should get None" >> {
        accuracy(forecast, actual) === Map(T1 -> None)
      }
    }
    "When forecast is 0 and actual is 10,000" >> {
      val forecast = 0
      val actual = 10000
      "Then I should get -100%" >> {
        accuracy(forecast, actual) === Map(T1 -> Option(-100))
      }
    }
  }

  private def accuracy(forecast: Int, actual: Int): Map[Terminal, Option[Double]] = {
    val date = LocalDate(2022, 7, 4)
    val acc = AccuracyForDate(date, (_, _) => Future.successful(Map(T1 -> forecast)), Map(T1 -> actual), LocalDate(2022, 7, 5))
    Await.result(acc.accuracy(date, 1).getOrElse(Future.successful(Map())), 1.second)
  }
}
