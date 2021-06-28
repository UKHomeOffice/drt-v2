package controllers.application

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes.Accepted
import akka.testkit.{TestKit, TestProbe}
import dispatch.Future
import org.specs2.mutable.SpecificationLike
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc.{AnyContentAsEmpty, DefaultActionBuilder, Headers}
import play.api.test.Helpers.{GET, route, _}
import play.api.test.{FakeRequest, WithApplication}

import java.util.TimeZone
import scala.concurrent.ExecutionContextExecutor

class WithImportsTest extends TestKit(ActorSystem("with-imports")) with SpecificationLike {
  TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
  System.setProperty("user.timezone", "UTC")

  implicit val ec: ExecutionContextExecutor = system.dispatcher

  val flightsProbe: TestProbe = TestProbe("")

  val appWithRoutes: Application = GuiceApplicationBuilder()
    .appRoutes { app =>
      println("Hit the router setup")
      val Action = app.injector.instanceOf[DefaultActionBuilder]
      ({
        case ("GET", "/data/feed/red-list-counts") =>
          println("Hit the route")
          WithImports.feedImportRedListCounts(flightsProbe.ref)
      })
    }
    .build()

  "respond to the index Action" in new WithApplication(appWithRoutes) {
    val headers: Headers = Headers(("X-Auth-Roles", "port-feed-upload"))
    val Some(result) = route(app, FakeRequest(GET, "/data/feed/red-list-counts", headers, body = AnyContentAsEmpty))

    status(result) must equalTo(OK)
//    contentType(result) must beSome("text/html")
//    charset(result) must beSome("utf-8")
//    contentAsString(result) must contain("Hello Bob")
  }

}
