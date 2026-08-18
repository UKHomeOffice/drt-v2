package drt.client

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class SPAMainTitleSpec extends AnyFunSuite with Matchers {
  test("browserTitleFor returns the normal title for non-404 routes") {
    BrowserTitle.forNotFound(isNotFound = false) shouldBe "Dynamic Response Tool - Border Force"
  }

  test("browserTitleFor returns the 404 title for NotFoundLoc") {
    BrowserTitle.forNotFound(isNotFound = true) shouldBe "Page not found - Dynamic Response Tool - Border Force"
  }
}

