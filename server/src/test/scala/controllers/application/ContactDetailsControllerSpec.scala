package controllers.application

import akka.util.Timeout
import module.DrtModule
import org.scalatestplus.play.PlaySpec
import play.api.test.Helpers._
import play.api.test._
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.config.Lhr


class ContactDetailsControllerSpec extends PlaySpec {
  "ContactDetailsController" should {

    "get contact details" in {
      val config = Lhr.config.copy(
        contactEmail = Some("test@test.com"),
        outOfHoursContactPhone = Some("0123456789"))

      val module: DrtModule = new TestDrtModule(config)

      implicit val timeout: Timeout = module.timeout

      val drtSystemInterface: DrtSystemInterface = module.provideDrtSystemInterface

      val controller = new ContactDetailsController(Helpers.stubControllerComponents(), drtSystemInterface)

      val result = controller.getContactDetails.apply(FakeRequest().withHeaders("X-Forwarded-Email" -> "test@test.com",
        "X-Forwarded-Preferred-Username" -> "test",
        "X-Forwarded-User" -> "test",
        "X-Forwarded-Groups" -> s"TEST"))

      status(result)(timeout) mustBe OK

      val resultExpected =
        s"""{"supportEmail":["test@test.com"],"oohPhone":["0123456789"]}"""
          .stripMargin

      contentAsString(result)(timeout) must include(resultExpected)
    }
  }
}
