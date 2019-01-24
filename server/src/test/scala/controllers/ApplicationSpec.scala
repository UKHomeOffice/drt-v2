package controllers

import akka.stream.{ActorMaterializer, Materializer}
import org.joda.time.DateTime
import org.specs2.matcher.Scope
import play.api.Environment
import services.SDate
import services.crunch.CrunchTestLike

import scala.concurrent.ExecutionContext.global

class ApplicationSpec extends CrunchTestLike {

  trait Context extends Scope {
    implicit val mat: Materializer = ActorMaterializer()
    implicit val config = play.api.Configuration.from(Map("portCode" -> "test", "dq.s3.bucket" -> "bucket", "googleTrackingCode"-> "", "virus-scanner-url" -> ""))

    val application = new Application()(config = config, mat = mat, env = Environment.simple(), system = system, ec = global)
  }

  "Application" should {
    "isInRangeOnDay should be True of the last date-time in a given date range" in new Context {
      val anHourAgo: DateTime = DateTime.now.minusHours(1)
      val now: DateTime = DateTime.now
      val startDateTime = SDate(anHourAgo)
      val endDateTime = SDate(now)
      application.isInRangeOnDay(startDateTime, endDateTime)(endDateTime) must beTrue
    }
  }

}
