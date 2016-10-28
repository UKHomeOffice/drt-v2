package services


import controllers.LHRFlightFeed
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import utest._

import spatutorial.shared._
import utest._

import akka.actor.ActorSystem
import scala.concurrent.{Future, Await}
import scala.util.{Success, Failure}
import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits.global
import akka.testkit.{TestKit, TestActors, DefaultTimeout, ImplicitSender}
import controllers.SystemActors
import controllers.Core


import scala.util.{Failure, Success}

object LHRCsvTests extends TestSuite {

  def tests = TestSuite {
    "can load LHR csv" - {
      val feed = LHRFlightFeed()
      // val csvLoad = LHRFlightFeflight.statused(null) csvLoad.csvContent(0)

      println("nonoflights, " + feed.lhrFlights.toList.map {
        case Success(s) => s
        case Failure(f) => f.toString
      }.mkString("\n"))
      assert(false)
    }
    "split an empty string of ,,," - {
      val r = ",,,".split(",", -1)
      println(s"r is ${r.toList}")
      assert(r == List("", "", "", ""))
    }
    "can parse the backwards LHR date format like '05:19 22/10/2016' to a sane joda time" - {
      val dateString = "05:19 22/10/2016"
      import org.joda.time.DateTime
      val parsed = LHRFlightFeed.parseDateTime(dateString)
      val expected: DateTime = new DateTime(2016, 10, 22, 5, 19)
      assert(parsed == expected)
    }
    "can parse the backwards LHR date format like with a 24hr time '20:19 22/10/2016' to a sane joda time" - {
      val dateString = "20:19 22/10/2016"
      import org.joda.time.DateTime
      val parsed = LHRFlightFeed.parseDateTime(dateString)
      val expected: DateTime = new DateTime(2016, 10, 22, 20, 19)
      assert(parsed == expected)
    }
  }
}
