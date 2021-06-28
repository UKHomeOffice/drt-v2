package controllers.application

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import org.specs2.mutable.SpecificationLike
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc.{AnyContentAsEmpty, DefaultActionBuilder, Headers, Request}
import play.api.test.Helpers.{GET, route, _}
import play.api.test.{FakeRequest, WithApplication}

import java.util.TimeZone
import scala.concurrent.ExecutionContextExecutor

class WithImports2Test extends TestKit(ActorSystem("with-imports")) with SpecificationLike {
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  val flightsProbe: TestProbe = TestProbe("")

  "The red list counts import action" should {
    "Do something" in {
      val x = WithImports.feedImportRedListCounts(flightsProbe.ref)(defaultAwaitTimeout, ec)(FakeRequest("GET","something"))
      x === "yeah"
    }
  }
}
