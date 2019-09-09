package drt.client.components

import drt.client.services.JSDateConversions._
import utest.{TestSuite, _}

object EnvironmentWarningTests extends TestSuite {


  def tests = Tests {
    "When testing the URL for DRT to determine the environment" - {
      "Given a prod-like URL the environment should be PROD" - {
        val url = "http://test.drt.homeoffice.gov.uk"

        val result = EnvironmentWarningComponent.envFromUrl(url)

        val expected = "PROD"

        assert(result == expected)
      }

      "Given a preprod-like URL the environment should be PROD" - {
        val url = "http://test.drt-preprod.homeoffice.gov.uk"

        val result = EnvironmentWarningComponent.envFromUrl(url)

        val expected = "PREPROD"

        assert(result == expected)
      }

      "Given a staging-like URL the environment should be PROD" - {
        val url = "http://test.drt-staging.homeoffice.gov.uk"

        val result = EnvironmentWarningComponent.envFromUrl(url)

        val expected = "STAGING"

        assert(result == expected)
      }

      "Given a test-like URL the environment should be PROD" - {
        val url = "http://localhost"

        val result = EnvironmentWarningComponent.envFromUrl(url)

        val expected = "TEST"

        assert(result == expected)
      }

    }
  }
}
