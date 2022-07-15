package services.accuracy

import services.{Accuracy, SDate}
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}


class AccuracySpec extends CrunchTestLike {
  "Given forecast and actual numbers of passengers and I ask for the accuracy stat" >> {
    "When forecast is 9,000 and actual is 10,000" >> {
      val forecast = 9000
      val actual = 10000
      "Then I should get 90%" >> {
        accuracy(forecast, actual) === Map(T1 -> 90)
      }
    }
    "When forecast is 11,000 and actual is 10,000" >> {
      val forecast = 11000
      val actual = 10000
      "Then I should get 90%" >> {
        accuracy(forecast, actual) === Map(T1 -> 90)
      }
    }
    "When forecast is 11,000 and actual is 0" >> {
      val forecast = 11000
      val actual = 0
      "Then I should get 0%" >> {
        accuracy(forecast, actual) === Map(T1 -> 0)
      }
    }
    "When forecast is 0 and actual is 10,000" >> {
      val forecast = 0
      val actual = 10000
      "Then I should get 0%" >> {
        accuracy(forecast, actual) === Map(T1 -> 0)
      }
    }
  }

  private def accuracy(forecast: Int, actual: Int): Map[Terminal, Double] = {
    val acc = Accuracy((_, _) => Future.successful(Map(T1 -> forecast)), _ => Future.successful(Map(T1 -> actual)))
    Await.result(acc.accuracy(LocalDate(2022, 7, 4), SDate("2022-07-04")), 1.second)
  }
}
