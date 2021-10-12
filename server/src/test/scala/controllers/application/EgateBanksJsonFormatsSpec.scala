package controllers.application

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, SetEgateBanksUpdate}
import uk.gov.homeoffice.drt.ports.Terminals.T2

class EgateBanksJsonFormatsSpec extends Specification {
  val jsonStr =
    """{
      |  "$type": "uk.gov.homeoffice.drt.egates.SetEgateBanksUpdate",
      |  "terminal": {"$type": "uk.gov.homeoffice.drt.ports.Terminals.T2"},
      |  "originalDate": 1633518000000,
      |  "egateBanksUpdate": {
      |    "effectiveFrom": 1633518000000,
      |    "banks": [
      |      {"gates":[true,true,true,true,false,false,false,true,true,true]},
      |      {"gates":[true,true,true,true,true]}
      |    ]
      |  }
      |}""".stripMargin
  "JsonFormats " should {
    "Be able to parse json submitted from the front end" in {
      val terminal = T2

      import spray.json._

      implicit val rdu = EgateBanksJsonFormats.egateBanksUpdateJsonFormat
      implicit val rdus = EgateBanksJsonFormats.egateBanksUpdatesJsonFormat
      implicit val srdu = EgateBanksJsonFormats.setEgateBanksUpdatesJsonFormat

      val setUpdate = jsonStr.parseJson.convertTo[SetEgateBanksUpdate]

      val expected = SetEgateBanksUpdate(
        terminal,
        1633518000000L,
        EgateBanksUpdate(
          1633518000000L,
          Vector(
            EgateBank(Vector(true, true, true, true, false, false, false, true, true, true)),
            EgateBank(Vector(true, true, true, true, true)))
        )
      )
      setUpdate === expected
    }
  }
}
