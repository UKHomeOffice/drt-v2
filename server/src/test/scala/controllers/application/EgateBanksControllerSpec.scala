package controllers.application

import module.DRTModule
import org.scalatestplus.play.PlaySpec
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}

class EgateBanksControllerSpec extends PlaySpec {

  "EgateBanksController" should {

    "get port e-gate details updates" in {

      val module = new DRTModule() {
        override lazy val isTestEnvironment: Boolean = true
      }

      val drtSystemInterface = module.provideDrtSystemInterface

      val controller = new EgateBanksController(Helpers.stubControllerComponents(), drtSystemInterface)

      val result = controller.getEgateBanksUpdates.apply(FakeRequest().withHeaders("X-Auth-Email" -> "test@test.com",
        "X-Auth-Username" -> "test",
        "X-Auth-Userid" -> "test",
        "X-Auth-Roles" -> s"TEST"))

      status(result) mustBe OK

      val typeString = "$type"

      val resultExpected =
        s"""{"updatesByTerminal":[["uk.gov.homeoffice.drt.ports.Terminals.T1",{"updates":[{"effectiveFrom":1577836800000,"banks":[{"$typeString":"uk.gov.homeoffice.drt.egates.EgateBank","gates":[true,true,true,true,true,true,true,true,true,true]},{"$typeString":"uk.gov.homeoffice.drt.egates.EgateBank","gates":[true,true,true,true,true,true,true,true,true,true]},{"$typeString":"uk.gov.homeoffice.drt.egates.EgateBank","gates":[true,true,true,true,true,true,true,true,true,true]}]}]}]]}"""
          .stripMargin

      contentAsString(result) must include(resultExpected)
    }
  }
}
