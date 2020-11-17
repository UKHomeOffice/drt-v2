package drt.client.components

import drt.shared.PortCode
import utest.{TestSuite, _}

object AppUrlsTests extends TestSuite {
  def tests: Tests = Tests {
    "Given a live url for LHR" - {
      "When I ask for the url for STN" - {
        "Then I should get the same url but with lhr replaced with stn" - {
          val lhrUrl = "https://lhr.drt.homeoffice.gov.uk/"
          val urls = AppUrls(lhrUrl)

          val stnUrl = urls.urlForPort(PortCode("STN"))

          assert(stnUrl == "https://stn.drt.homeoffice.gov.uk")
        }
      }
    }
  }
}
