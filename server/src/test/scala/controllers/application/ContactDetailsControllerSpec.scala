package controllers.application

import controllers.DrtActorSystem
import module.DRTModule
import org.scalatestplus.play.PlaySpec
import play.api.test.Helpers.contentAsString
import play.api.test.Helpers._
import play.api.test._

class ContactDetailsControllerSpec extends PlaySpec {

  "ContactDetailsController" should {

    "get contact details" in {

      val module = new DRTModule() {
        override lazy val isTestEnvironment: Boolean = true
        override lazy val airportConfig = DrtActorSystem.airportConfig.copy(
          contactEmail = Some("test@test.com"),
          outOfHoursContactPhone = Some("0123456789")

        )
      }

      val drtSystemInterface = module.provideDrtSystemInterface

      val controller = new ContactDetailsController(Helpers.stubControllerComponents(), drtSystemInterface)

      val result = controller.getContactDetails.apply(FakeRequest().withHeaders("X-Auth-Email" -> "test@test.com",
        "X-Auth-Username" -> "test",
        "X-Auth-Userid" -> "test",
        "X-Auth-Roles" -> s"TEST"))

      status(result) mustBe OK

      val resultExpected =
        s"""{"supportEmail":["test@test.com"],"oohPhone":["0123456789"]}"""
          .stripMargin

      contentAsString(result) must include(resultExpected)
    }
  }
}
