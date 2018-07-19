package controllers

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.joda.time.DateTime
import org.specs2.matcher.Scope
import org.specs2.mutable.SpecificationLike
import play.api.Environment
import services.SDate

import scala.concurrent.ExecutionContext.global

class ApplicationSpec extends TestKit(ActorSystem("testActorSystem", ConfigFactory.empty())) with SpecificationLike {

  trait Context extends Scope {
    implicit val mat: Materializer = ActorMaterializer()
    implicit val config = play.api.Configuration.from(Map("portCode" -> "test", "dq.s3.bucket" -> "bucket"))

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
