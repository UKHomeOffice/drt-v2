package controllers.application

import org.scalatestplus.play.PlaySpec
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}

class EgateBanksControllerSpec extends PlaySpec {

  "EgateBanksController" should {
    val drtSystemInterface = new TestDrtModule().provideDrtSystemInterface

    "get port e-gate details updates" in {

      val controller = new EgateBanksController(Helpers.stubControllerComponents(), drtSystemInterface)

      val request = FakeRequest().withHeaders("X-Forwarded-Email" -> "test@test.com",
        "X-Forwarded-Preferred-Username" -> "test",
        "X-Forwarded-User" -> "test",
        "X-Forwarded-Groups" -> s"TEST")

      val result = controller.getEgateBanksUpdates.apply(request)

      status(result) mustBe OK

      val typeString = "$type"

      val resultExpected =
        s"""{"updatesByTerminal":[["uk.gov.homeoffice.drt.ports.Terminals.T1",{"updates":[{"effectiveFrom":1577836800000,"banks":[{"$typeString":"uk.gov.homeoffice.drt.egates.EgateBank","gates":[true,true,true,true,true,true,true,true,true,true]},{"$typeString":"uk.gov.homeoffice.drt.egates.EgateBank","gates":[true,true,true,true,true,true,true,true,true,true]},{"$typeString":"uk.gov.homeoffice.drt.egates.EgateBank","gates":[true,true,true,true,true,true,true,true,true,true]}]}]}]]}"""
          .stripMargin

      contentAsString(result) must include(resultExpected)
    }
  }
}
