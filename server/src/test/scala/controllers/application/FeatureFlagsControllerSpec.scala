package controllers.application

import org.scalatestplus.play.PlaySpec
import play.api.http.Status.OK
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import uk.gov.homeoffice.drt.testsystem.MockDrtParameters

class FeatureFlagsControllerSpec extends PlaySpec {

  "FeatureFlagsController" should {

    "get list of feature flag" in {

      val module = new TestDrtModule() {
        override lazy val drtParameters = new MockDrtParameters {
          override val useApiPaxNos = true
          override val enableToggleDisplayWaitTimes = true
          override val displayRedListInfo = true
        }
      }

      val drtSystemInterface = module.provideDrtSystemInterface

      val controller = new FeatureFlagsController(Helpers.stubControllerComponents(), drtSystemInterface)

      val result = controller.getFeatureFlags.apply(FakeRequest().withHeaders("X-Auth-Email" -> "test@test.com",
        "X-Auth-Username" -> "test",
        "X-Auth-Userid" -> "test",
        "X-Auth-Roles" -> s"TEST"))

      status(result) mustBe OK

      val resultExpected =
        s"""{"useApiPaxNos":true,"displayWaitTimesToggle":true,"displayRedListInfo":true}"""
          .stripMargin

      contentAsString(result) must include(resultExpected)
    }
  }
}
