package services

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse}
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.SpecificationLike
import services.graphstages.Crunch

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class OOHCheckerSpec extends TestKit(ActorSystem("testActorSystem", ConfigFactory.empty())) with SpecificationLike {
  sequential
  isolated

  val mockBankHolidayClient = new BankHolidayApiClient() with Holidays2019Success

  "During BST" >> {

    "Given a time on a weekday between 9 and 17:30 then OOH should be false" >> {

      val time = SDate("2019-08-09T09:01:00", Crunch.europeLondonTimeZone)

      val result = Await.result(OOHChecker(mockBankHolidayClient).isOOH(time), 1 second)

      val expected = false

      result === expected
    }

    "Given a time on a weekday before 9am then OOH should be true" >> {
      val time = SDate("2019-08-09T05:58:00", Crunch.europeLondonTimeZone)
      val result = Await.result(OOHChecker(mockBankHolidayClient).isOOH(time), 1 second)

      val expected = true

      result === expected
    }

    "Given a time on a weekday after 17:30pm then OOH should be true" >> {
      val time = SDate("2019-08-09T17:31:00", Crunch.europeLondonTimeZone)

      val result = Await.result(OOHChecker(mockBankHolidayClient).isOOH(time), 1 second)

      val expected = true

      result === expected
    }

    "Given a time on a weekend between 9 and 17:30 OOH should be true" >> {
      val time = SDate("2019-08-10T17:00:00", Crunch.europeLondonTimeZone)

      val result = Await.result(OOHChecker(mockBankHolidayClient).isOOH(time), 1 second)

      val expected = true

      result === expected
    }
  }

  "During UTC" >> {

    "Given a time on a weekday between 9 and 17:30 then OOH should be false" >> {

      val time = SDate("2019-01-09T09:01:00", Crunch.europeLondonTimeZone)

      val result = Await.result(OOHChecker(mockBankHolidayClient).isOOH(time), 1 second)

      val expected = false

      result === expected
    }

    "Given a time on a weekday before 9am then OOH should be true" >> {
      val time = SDate("2019-01-09T05:58:00", Crunch.europeLondonTimeZone)
      val result = Await.result(OOHChecker(mockBankHolidayClient).isOOH(time), 1 second)

      val expected = true

      result === expected
    }

    "Given a time on a weekday after 17:30pm then OOH should be true" >> {
      val time = SDate("2019-01-09T17:31:00", Crunch.europeLondonTimeZone)

      val result = Await.result(OOHChecker(mockBankHolidayClient).isOOH(time), 1 second)

      val expected = true

      result === expected
    }
  }

  "Bank Holidays" >> {
    "Given a bank Holiday json array I should get back a list of bank holidays" >> {
      val client = new BankHolidayApiClient() with GetHolidaysSuccess

      val result = Await.result(client.getHolidays, 1 second)

      val expected = Map(
        "england-and-wales" -> BankHolidayDivision(
          "england-and-wales",
          List(
            BankHoliday("New Year’s Day", "2012-01-02", "Substitute day", true),
            BankHoliday("Good Friday", "2012-04-06", "", false)
          )
        )
      )

      result === expected
    }

    "Given christmas day 2019 I should get true when testing if it's a bank holiday" >> {
      val client = new BankHolidayApiClient() with Holidays2019Success

      val time = SDate("2019-12-25T17:00:00", Crunch.europeLondonTimeZone)

      val result = Await.result(client.isEnglandAndWalesBankHoliday(time), 1 second)

      val expected = true

      result === expected
    }

    "Given a normal day I should get false when testing if it's a bank holiday" >> {
      val client = new BankHolidayApiClient() with Holidays2019Success

      val time = SDate("2019-08-12T17:00:00", Crunch.europeLondonTimeZone)

      val result = Await.result(client.isEnglandAndWalesBankHoliday(time), 1 second)

      val expected = false

      result === expected
    }

    "Given a bank holiday between 09:00 and 17:30 I OOH should be true" >> {
      val time = SDate("2019-12-25T17:00:00", Crunch.europeLondonTimeZone)

      val result = Await.result(OOHChecker(mockBankHolidayClient).isOOH(time), 1 second)

      val expected = true

      result === expected
    }
  }

  trait GetHolidaysSuccess {
    self: BankHolidayApiClient =>

    override def sendAndReceive: HttpRequest => Future[HttpResponse] = {

      _: HttpRequest => Future(HttpResponse().withEntity(HttpEntity(ContentTypes.`application/json`, bankHolidaySuccessJson)))
    }
  }

  trait Holidays2019Success {
    self: BankHolidayApiClient =>

    override def sendAndReceive: HttpRequest => Future[HttpResponse] = {

      _: HttpRequest => Future(HttpResponse().withEntity(HttpEntity(ContentTypes.`application/json`, hols2019Success)))
    }
  }

  val bankHolidaySuccessJson: String =
    """
      |{
      |    "england-and-wales": {
      |        "division": "england-and-wales",
      |        "events": [
      |            {
      |                "title": "New Year’s Day",
      |                "date": "2012-01-02",
      |                "notes": "Substitute day",
      |                "bunting": true
      |            },
      |            {
      |                "title": "Good Friday",
      |                "date": "2012-04-06",
      |                "notes": "",
      |                "bunting": false
      |            }
      |        ]
      |    }
      |}
    """.stripMargin

  val hols2019Success: String =
    """
      |{
      |    "england-and-wales": {
      |        "division": "england-and-wales",
      |        "events": [
      |            {"title":"New Year’s Day","date":"2019-01-01","notes":"","bunting":true},
      |            {"title":"Good Friday","date":"2019-04-19","notes":"","bunting":false},
      |            {"title":"Easter Monday","date":"2019-04-22","notes":"","bunting":true},
      |            {"title":"Early May bank holiday","date":"2019-05-06","notes":"","bunting":true},
      |            {"title":"Spring bank holiday","date":"2019-05-27","notes":"","bunting":true},
      |            {"title":"Summer bank holiday","date":"2019-08-26","notes":"","bunting":true},
      |            {"title":"Christmas Day","date":"2019-12-25","notes":"","bunting":true},
      |            {"title":"Boxing Day","date":"2019-12-26","notes":"","bunting":true}
      |        ]
      |    }
      |}
    """.stripMargin
}
