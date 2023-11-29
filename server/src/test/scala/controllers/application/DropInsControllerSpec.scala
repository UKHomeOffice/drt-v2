package controllers.application

import email.GovNotifyEmail
import module.DRTModule
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.test.Helpers._
import play.api.test._
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface

class DropInsControllerSpec extends PlaySpec with MockitoSugar {
  "DropInsController" should {
    "get published drop-ins" in {
      val controller: DropInsController = dropInSessionsController

      val result = controller.dropIns().apply(FakeRequest())

      status(result) mustBe OK

      val resultExpected =
        s"""[{"id":[1],"title":"test","startTime":1696687258000,"endTime":1696692658000,"isPublished":true,"meetingLink":[],"lastUpdatedAt":1695910303210}]"""
          .stripMargin

      contentAsString(result) must include(resultExpected)
    }

    "get drop-ins in registration" in {
      val controller: DropInsController = dropInSessionsController

      val result = controller.getDropInRegistrations().apply(FakeRequest())

      status(result) mustBe OK

      val resultExpected =
        s"""[{"email":"someone@test.com","dropInId":1,"registeredAt":1695910303210,"emailSentAt":[1695910303210]}]"""
          .stripMargin

      contentAsString(result) must include(resultExpected)
    }

    "create drop-ins registration" in {
      val controller: DropInsController = dropInSessionsController

      val result = controller.createDropInRegistration().apply(FakeRequest().withTextBody(""""1"""")
        .withHeaders("X-Auth-Email" -> "someone@test.com", "X-Auth-Roles" -> "border-force-staff,TEST"))

      status(result) mustBe OK

      contentAsString(result) must include("Successfully registered drop-ins")
    }
  }

  private def dropInSessionsController = {
    val module = new DRTModule() {
      override val isTestEnvironment: Boolean = true
    }

    val drtSystemInterface: DrtSystemInterface = module.provideDrtSystemInterface

    val govNotify = mock[GovNotifyEmail]

    new DropInsController(Helpers.stubControllerComponents(), drtSystemInterface, govNotify)
  }
}
