package controllers

import email.GovNotifyEmail
import module.DRTModule
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.test.Helpers._
import play.api.test._

class DropInsControllerSpec extends PlaySpec with MockitoSugar {

  "DropInsController" should {

    "get published drop-ins" in {

      val module = new DRTModule() {
        override lazy val isTestEnvironment: Boolean = true
      }

      val drtSystemInterface = module.provideDrtSystemInterface
      val govNotify = mock[GovNotifyEmail]

      val controller = new DropInsController(Helpers.stubControllerComponents(), drtSystemInterface, govNotify)

      val result = controller.dropIns().apply(FakeRequest())

      status(result) mustBe OK

      val resultExpected =
        s"""[{"id":[1],"title":"test","startTime":1696687258000,"endTime":1696692658000,"isPublished":true,"meetingLink":[],"lastUpdatedAt":1695910303210}]"""
          .stripMargin

      contentAsString(result) must include(resultExpected)
    }
  }
}
