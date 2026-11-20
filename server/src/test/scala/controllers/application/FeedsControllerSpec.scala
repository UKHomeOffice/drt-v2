package controllers.application

import org.scalatestplus.play.PlaySpec
import play.api.http.Status.OK
import play.api.test.Helpers._
import play.api.test.{ FakeRequest, Helpers }
import uk.gov.homeoffice.drt.ports.{ AclFeedSource, ForecastFeedSource, LiveFeedSource }

class FeedsControllerSpec extends PlaySpec {

  "FeedsController" should {

    val drtSystemInterface = new TestDrtModule().provideDrtSystemInterface

    implicit val mat = drtSystemInterface.materializer

    "get feed status" in {

      val controller = new FeedsController(Helpers.stubControllerComponents(), drtSystemInterface)

      val request = FakeRequest().withHeaders(
        "X-Forwarded-Email" -> "test@test.com",
        "X-Forwarded-Preferred-Username" -> "test",
        "X-Forwarded-User" -> "test",
        "X-Forwarded-Groups" -> s"TEST"
      )

      val result = controller.getFeedStatuses.apply(request)

      status(result) mustBe OK

      val resultExpected = "[]"

      contentAsString(result) must include(resultExpected)
    }

    "return only arrival-source actors enabled for the airport config" in {
      val sources = FeedsController.arrivalActorPersistenceIdsFor(Seq(AclFeedSource, LiveFeedSource))
        .map(_._2)

      sources must contain theSameElementsAs Seq(AclFeedSource, LiveFeedSource)
      sources must not contain ForecastFeedSource
    }
  }
}
