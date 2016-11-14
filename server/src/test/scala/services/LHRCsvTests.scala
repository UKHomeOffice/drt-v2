package services


import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.stream.testkit.TestSubscriber.Probe
import akka.stream.testkit.scaladsl.TestSink
import controllers.LHRFlightFeed
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.specs2.execute.Result
import org.specs2.mutable.SpecificationLike
import utest._

import spatutorial.shared._
import utest._

import akka.actor.ActorSystem
import scala.concurrent.duration._
import scala.concurrent.{Future, Await}
import scala.util.{Success, Failure}
import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits.global
import akka.testkit.{TestKit, TestActors, DefaultTimeout, ImplicitSender}
import controllers.SystemActors
import controllers.Core


import scala.util.{Failure, Success}

class StreamFlightCrunchTests extends TestKit(ActorSystem()) with SpecificationLike {
  implicit val mat = ActorMaterializer()

  implicit def probe2Success[R <: Probe[_]](r: R): Result = success

  "Streamed Flight tests" >> {
    "can split a stream into two materializers" in {
      val source = Source(List(1, 2, 3, 4))
      val doubled = source.map((x) => x * 2)
      val squared = source.map((x) => x * x)
      val actDouble = doubled
        .runWith(TestSink.probe[Int])
        .toStrict(FiniteDuration(1, SECONDS))

      assert(actDouble == List(2, 4, 6, 8))
      val actSquared = squared
        .runWith(TestSink.probe[Int])
        .toStrict(FiniteDuration(1, SECONDS))

      actSquared == List(1, 4, 9, 16)
    }

    "we do a crunch on flight changes" in {
      import WorkloadCalculatorTests._
      val flightsSource = Source(List(
        List(apiFlight("BA123", totalPax = 200, scheduledDatetime = "2016-09-01T10:31")),
        List(apiFlight("BA123", totalPax = 100, scheduledDatetime = "2016-09-01T10:31"))))

      true
    }
  }
}

object LHRCsvTests extends TestSuite {

  def tests = TestSuite {
    "can load LHR csv" - {
      val feed = LHRFlightFeed()
      // val csvLoad = LHRFlightFeflight.statused(null) csvLoad.csvContent(0)

      println("nonoflights, " + feed.lhrFlights.toList.map {
        case Success(s) => s
        case Failure(f) => f.toString
          assert(false)
      }.mkString("\n"))
    }
    "split an empty string of ,,," - {
      val r = ",,,".split(",", -1).toList
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
