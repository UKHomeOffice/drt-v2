package filters

import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc._
import play.api.test._
import scala.concurrent.ExecutionContext

class ACPRedirectFilterSpec (implicit ec :ExecutionContext) extends PlaySpecification with Results {

  val configWithAcpRedirectNotSet = play.api.Configuration.from(Map("portcode" -> "test", "dq.s3.bucket" -> "bucket", "googleTrackingCode"-> "", "feature-flags.acp-redirect" -> false))
  val configWithAcpRedirectSet = configWithAcpRedirectNotSet ++ play.api.Configuration.from(Map("feature-flags.acp-redirect" -> true))

  "Passes through the app when the ACP redirect flag is not set" in new WithApplication(GuiceApplicationBuilder(configuration = configWithAcpRedirectNotSet).build()) {
    val dummyEndpoint: EssentialAction = Action (Ok("hello world"))
    val request = FakeRequest(GET, "/")
    implicit val config = configWithAcpRedirectNotSet

    val filter = new ACPRedirectFilter().apply(dummyEndpoint)
    val result = call(filter, request)

    status(result) mustEqual OK
    contentAsString(result) mustEqual "hello world"
  }

  "Redirects to the acp version when the ACP redirect flag is set" in new WithApplication(GuiceApplicationBuilder(configuration = configWithAcpRedirectSet).build()) {
    val dummyEndpoint: EssentialAction = Action (Ok("hello world"))
    val request = FakeRequest(GET, "/")
    implicit val config = configWithAcpRedirectSet

    val filter = new ACPRedirectFilter().apply(dummyEndpoint)
    val result = call(filter, request)

    status(result) mustEqual SEE_OTHER
    redirectLocation(result) mustEqual Option("https://test.drt.homeoffice.gov.uk/v2/test/live")
  }

}
