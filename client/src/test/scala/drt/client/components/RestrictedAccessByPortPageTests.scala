package drt.client.components

import utest.{TestSuite, _}

class RestrictedAccessByPortPageTests extends TestSuite {
  override def tests: Tests = {
    "Given a live url for LHR" - {
      "When I ask for the url for STN" - {
        "Then I should get the same url but with lhr replaced with stn" - {
          val lhrUrl = "lhr.drt.homeoffice.gov.uk"

          val stnUrl = RestrictedAccessByPortPage.url(lhrUrl)

          assert(stnUrl == "stn.drt.homeoffice.gov.uk")
        }
      }
    }
  }
}
