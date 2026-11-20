package drt.client.components

import utest.{ TestSuite, _ }

object AlertsComponentTests extends TestSuite {

  def tests: Tests = Tests {
    test("When testing the URL for DRT to determine the environment") {
      test("Given a prod-like URL the environment should be PROD") {
        val url = "http://test.drt.homeoffice.gov.uk"

        val result = AlertsComponent.nonProdEnvFromUrl(url)

        assert(result.isEmpty)
      }

      test("Given a preprod-like URL the environment should be PROD") {
        val url = "http://test.drt-preprod.homeoffice.gov.uk"

        val result = AlertsComponent.nonProdEnvFromUrl(url)

        assert(result == Option("PREPROD"))
      }

      test("Given a staging-like URL the environment should be PROD") {
        val url = "http://test.drt-staging.homeoffice.gov.uk"

        val result = AlertsComponent.nonProdEnvFromUrl(url)

        assert(result == Option("STAGING"))
      }

      test("Given a test-like URL the environment should be PROD") {
        val url = "http://localhost"

        val result = AlertsComponent.nonProdEnvFromUrl(url)

        assert(result == Option("TEST"))
      }

    }
  }
}
