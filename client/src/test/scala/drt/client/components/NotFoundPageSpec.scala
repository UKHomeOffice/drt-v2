package drt.client.components

import org.scalajs.dom
import utest._

object NotFoundPageSpec extends TestSuite {
  def tests: Tests = Tests {
    test("NotFoundPage renders the expected GOV.UK content") {
      val container = dom.document.createElement("div")
      dom.document.body.appendChild(container)

      try {
        NotFoundPage().renderIntoDOM(container)

        val main = container.querySelector("main#main-content")
        assert(main != null)
        assert(main.getAttribute("role") == "main")
        assert(main.classList.contains("govuk-main-wrapper"))
        assert(main.classList.contains("govuk-main-wrapper--l"))

        val heading = container.querySelector("h1.govuk-heading-l")
        assert(heading != null)
        assert(heading.textContent.trim == "Page not found")

        val paragraphs = container.querySelectorAll("p.govuk-body")
        assert(paragraphs.length == 3)
        assert(paragraphs(0).textContent.trim == "If you typed the web address, check it is correct.")
        assert(paragraphs(1).textContent.trim == "If you pasted the web address, check you copied the entire address.")
        assert(paragraphs(2).textContent.contains("If the web address is correct or you selected a link or button, please try again"))
        assert(paragraphs(2).textContent.contains("or email the DRT team at"))
        assert(paragraphs(2).textContent.contains("drtpoiseteam@homeoffice.gov.uk."))
        val emailLink = container.querySelector("a.govuk-link")
        assert(emailLink != null)
        assert(emailLink.getAttribute("href") == "#")
        assert(emailLink.textContent.trim == "drtpoiseteam@homeoffice.gov.uk")
      } finally {
        dom.document.body.removeChild(container)
      }
    }
  }
}
