package drt.client.components

import utest.{TestSuite, _}

object AlertsComponentTests extends TestSuite {


  def tests: Tests = Tests {
    "When testing the URL for DRT to determine the environment" - {
      "Given a prod-like URL the environment should be PROD" - {
        val url = "http://test.drt.homeoffice.gov.uk"

        val result = AlertsComponent.nonProdEnvFromUrl(url)

        assert(result.isEmpty)
      }

      "Given a preprod-like URL the environment should be PROD" - {
        val url = "http://test.drt-preprod.homeoffice.gov.uk"

        val result = AlertsComponent.nonProdEnvFromUrl(url)

        assert(result == Option("PREPROD"))
      }

      "Given a staging-like URL the environment should be PROD" - {
        val url = "http://test.drt-staging.homeoffice.gov.uk"

        val result = AlertsComponent.nonProdEnvFromUrl(url)

        assert(result == Option("STAGING"))
      }

      "Given a test-like URL the environment should be PROD" - {
        val url = "http://localhost"

        val result = AlertsComponent.nonProdEnvFromUrl(url)

        assert(result == Option("TEST"))
      }

    }
  }
}
