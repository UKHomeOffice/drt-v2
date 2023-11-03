package controllers.application

import module.DRTModule
import org.scalatestplus.play.PlaySpec
import play.api.test.Helpers._
import play.api.test._
import test.MockDrtParameters
import uk.gov.homeoffice.drt.auth.Roles.ArrivalsAndSplitsView

class WalkTimeControllerSpec extends PlaySpec {

  "WalkTimeController" should {

    "get stand and gate walk times" in {

      val module = new DRTModule() {
        override lazy val isTestEnvironment: Boolean = true
        override lazy val mockDrtParameters = new MockDrtParameters {
          override val gateWalkTimesFilePath: Option[String] = Some(getClass.getClassLoader.getResource("gateWalktime.csv").getPath)

          override val standWalkTimesFilePath: Option[String] = Some(getClass.getClassLoader.getResource("standWalktime.csv").getPath)
        }
      }

      val controller = new WalkTimeController(Helpers.stubControllerComponents(), module.provideDrtSystemInterface)

      val result = controller.getWalkTimes.apply(FakeRequest().withHeaders("X-Auth-Email" -> "test@test.com",
        "X-Auth-Username" -> "test",
        "X-Auth-Userid" -> "test",
        "X-Auth-Roles" -> s"TEST,${ArrivalsAndSplitsView.name}"))

      status(result) mustBe OK

      val resultExpected =
        s"""{"byTerminal":[["uk.gov.homeoffice.drt.ports.Terminals.T1",{"gateWalktimes":{"A1":{"gateOrStand":"A1","terminal":"uk.gov.homeoffice.drt.ports.Terminals.T1","walkTimeMillis":120000}},"standWalkTimes":{"1":{"gateOrStand":"1","terminal":"uk.gov.homeoffice.drt.ports.Terminals.T1","walkTimeMillis":120000}}}]]}""".stripMargin

      contentAsString(result) must include(resultExpected)
    }

  }
}



