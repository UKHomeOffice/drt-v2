package controllers.application

import module.DRTModule
import org.scalatestplus.play.PlaySpec
import play.api.http.Status.OK
import play.api.test.{FakeRequest, Helpers}
import play.api.test.Helpers.{contentAsString, status}
import play.api.test.Helpers._

class FeatureFlagsControllerSpec extends PlaySpec {

  "FeatureFlagsController" should {

    "get list of feature flag" in {

      val module = new DRTModule() {
        override lazy val isTestEnvironment: Boolean = true
      }

      val drtSystemInterface = module.provideDrtSystemInterface

      val controller = new FeatureFlagsController(Helpers.stubControllerComponents(), drtSystemInterface)

      val result = controller.getFeatureFlags.apply(FakeRequest().withHeaders("X-Auth-Email" -> "test@test.com",
        "X-Auth-Username" -> "test",
        "X-Auth-Userid" -> "test",
        "X-Auth-Roles" -> s"TEST"))

      status(result) mustBe OK

      val resultExpected =
        s"""{"useApiPaxNos":true,"displayWaitTimesToggle":false,"displayRedListInfo":false}"""
          .stripMargin

      contentAsString(result) must include(resultExpected)
    }
  }
}
