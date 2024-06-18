package controllers.application

import org.scalatestplus.play.PlaySpec
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import uk.gov.homeoffice.drt.auth.Roles.{ArrivalsAndSplitsView, TEST}

class AirportInfoControllerSpec extends PlaySpec {

  "AirportInfoController" should {

    "get airport info for the portCode requested" in {

      val drtSystemInterface = new TestDrtModule().provideDrtSystemInterface

      val controller = new AirportInfoController(Helpers.stubControllerComponents(), drtSystemInterface)

      val result = controller.getAirportInfo.apply(FakeRequest(GET, "/airport-info?portCode=EDI")
        .withHeaders("X-Forwarded-Groups" -> s"${TEST.name},${ArrivalsAndSplitsView.name}")

      )

      status(result) mustBe OK

      val resultExpected =
        s"""[[{"iata":"EDI"},{"airportName":"Edinburgh","city":"Edinburgh","country":"United Kingdom","code":"EDI"}]]"""
          .stripMargin

      contentAsString(result) must include(resultExpected)
    }

  }
}
