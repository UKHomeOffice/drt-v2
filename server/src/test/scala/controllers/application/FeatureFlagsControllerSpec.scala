package controllers.application

import actors.DrtParameters
import org.scalatestplus.play.PlaySpec
import play.api.http.Status.OK
import play.api.libs.json.Json
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import uk.gov.homeoffice.drt.testsystem.MockDrtParameters

class FeatureFlagsControllerSpec extends PlaySpec {

  "FeatureFlagsController" should {

    "get list of feature flag" in {

      val module: TestDrtModule = new TestDrtModule() {
        override lazy val drtParameters: DrtParameters = new MockDrtParameters {
          override val useApiPaxNos = true
          override val enableToggleDisplayWaitTimes = true
          override val displayRedListInfo = true
          override val enableShiftPlanningChange = true
          override val enableStaffingPageWarnings = true
        }
      }

      val drtSystemInterface = module.provideDrtSystemInterface

      val controller = new FeatureFlagsController(Helpers.stubControllerComponents(), drtSystemInterface)

      val result = controller.getFeatureFlags.apply(FakeRequest().withHeaders("X-Forwarded-Email" -> "test@test.com",
        "X-Forwarded-Preferred-Username" -> "test",
        "X-Forwarded-User" -> "test",
        "X-Forwarded-Groups" -> s"TEST"))

      status(result) mustBe OK

      val jsonContent = Json.parse(contentAsString(result))
      val featureFlags = jsonContent.as[Map[String, Boolean]]

      featureFlags("useApiPaxNos") mustBe true
      featureFlags("displayWaitTimesToggle") mustBe true
      featureFlags("displayRedListInfo") mustBe true
      featureFlags("enableShiftPlanningChange") mustBe true
      featureFlags("enableStaffingPageWarnings") mustBe true
    }
  }
}
