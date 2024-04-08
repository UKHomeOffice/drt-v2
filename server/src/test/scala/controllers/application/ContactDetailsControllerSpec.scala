package controllers.application

import akka.util.Timeout
import controllers.DrtConfigSystem
import module.DRTModule
import org.scalatestplus.play.PlaySpec
import play.api.test.Helpers._
import play.api.test._
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.AirportConfig


class ContactDetailsControllerSpec extends PlaySpec {
  "ContactDetailsController" should {

    "get contact details" in {
      val origConfig = new DrtConfigSystem().airportConfig
      val drtConfigSystemI = new DrtConfigSystem() {
        override val isTestEnvironment: Boolean = true
        override def airportConfig: AirportConfig = origConfig.copy(
          contactEmail = Some("test@test.com"),
          outOfHoursContactPhone = Some("0123456789"))
      }

      val module: DRTModule = new DRTModule() {
        override lazy val drtConfigSystem = drtConfigSystemI
      }

      implicit val timeout: Timeout = module.timeout

      val drtSystemInterface: DrtSystemInterface = module.provideDrtSystemInterface

      val controller = new ContactDetailsController(Helpers.stubControllerComponents(), drtSystemInterface)

      val result = controller.getContactDetails.apply(FakeRequest().withHeaders("X-Auth-Email" -> "test@test.com",
        "X-Auth-Username" -> "test",
        "X-Auth-Userid" -> "test",
        "X-Auth-Roles" -> s"TEST"))

      status(result)(timeout) mustBe OK

      val resultExpected =
        s"""{"supportEmail":["test@test.com"],"oohPhone":["0123456789"]}"""
          .stripMargin

      contentAsString(result)(timeout) must include(resultExpected)
    }
  }
}
