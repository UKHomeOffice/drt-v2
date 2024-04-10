package controllers.application

import module.DRTModule
import org.scalatestplus.play.PlaySpec
import play.api.http.Status.OK
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}

class FeedsControllerSpec extends PlaySpec {

  "FeedsController" should {

    val module = new DRTModule() {
      override val isTestEnvironment: Boolean = true
    }

    val drtSystemInterface = module.provideDrtSystemInterface

    implicit val mat = drtSystemInterface.materializer

    "get feed status" in {

      val controller = new FeedsController(Helpers.stubControllerComponents(), drtSystemInterface)

      val request = FakeRequest().withHeaders("X-Auth-Email" -> "test@test.com",
        "X-Auth-Username" -> "test",
        "X-Auth-Userid" -> "test",
        "X-Auth-Roles" -> s"TEST")

      val result = controller.getFeedStatuses.apply(request)

      status(result) mustBe OK

      val resultExpected = "[]"

      contentAsString(result) must include(resultExpected)
    }
  }
}

