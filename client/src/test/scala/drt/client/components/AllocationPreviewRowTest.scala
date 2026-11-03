package drt.client.components

import diode.data.Empty
import drt.client.components.TerminalDesksAndQueues.{ Deployments, FrozenPreview, Recommended }
import drt.client.services.JSDateConversions.SDate
import drt.client.services.ViewDay
import drt.shared.CrunchApi.StaffMinute
import drt.shared.airportconfig.Test
import org.scalajs.dom
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.StaffMovementsEdit
import uk.gov.homeoffice.drt.models.CrunchMinute
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.Terminals.T1
import utest._

object AllocationPreviewRowTest extends TestSuite {
  val day = SDate.now().getLocalLastMidnight
  val base: TerminalDesksAndQueuesRow.Props = TerminalDesksAndQueuesRow.Props(
    day.millisSinceEpoch,
    Seq(CrunchMinute(T1, EeaDesk, day.millisSinceEpoch, 10, 20, 3, 16, Some(12))),
    4,
    Some(3),
    StaffMinute(T1, day.millisSinceEpoch, 8, 1, 2, None),
    Map(EeaDesk -> 12),
    Test.config,
    Empty,
    T1,
    false,
    Recommended,
    false,
    ViewDay(day.toLocalDate, None),
    LoggedInUser("test", "test", "", Set(StaffMovementsEdit)),
    15,
    true,
    Seq(EeaDesk),
    Some(FrozenPreview(Seq(EeaDesk), Map(EeaDesk -> 15)))
  )

  def withRow(props: TerminalDesksAndQueuesRow.Props)(check: dom.Element => Unit): Unit = {
    val table = dom.document.createElement("table")
    val body = dom.document.createElement("tbody")
    table.appendChild(body)
    dom.document.body.appendChild(table)
    TerminalDesksAndQueuesRow(props).renderIntoDOM(body)
    try check(body)
    finally dom.document.body.removeChild(table)
  }

  val tests: Tests = Tests {
    test("Neutral cells use calculated SLA and both companion totals with no mutation affordances") {
      withRow(base) { body =>
        assert(!body.textContent.contains("Not calculated"))
        assert(body.querySelectorAll("td.red").length == 1)
        assert(body.querySelectorAll(".popover-trigger").length == 0)
        assert(body.querySelectorAll(".actuals").length == 0)
      }
    }
    test("available Neutral retains recommendation total and no staff adjustment controls") {
      val props = base.copy(
        viewType = Deployments,
        staffRec = 4,
        staffDep = Some(3),
        queueMinutes =
          base.queueMinutes.map(_.copy(deskRec = 0, waitTime = 0, deployedDesks = Some(2), deployedWait = Some(7))),
        preview = base.preview
      )
      withRow(props) { body =>
        assert(!body.textContent.contains("Not calculated"))
        assert(body.querySelectorAll("td.total-deployed.red").length == 0)
        assert(body.querySelectorAll(".popover-trigger").length == 0)
      }
    }
    test("uncalculated companions show dashes rather than plausible zero totals or RAG") {
      val availableOnly = base.copy(
        viewType = Deployments,
        staffRec = 0,
        queueMinutes = base.queueMinutes.map(_.copy(deskRec = 0, waitTime = 0, deployedDesks = Some(2))),
        preview = base.preview.map(_.copy(recommendedCalculated = false))
      )
      withRow(availableOnly) { body =>
        assert(body.querySelectorAll("td.total-deployed.red").length == 0)
        assert(body.querySelectorAll("td.total-deployed")(2).textContent == "-")
      }
      val recommendedOnly = base.copy(staffDep = None, preview = base.preview.map(_.copy(availableCalculated = false)))
      withRow(recommendedOnly) { body =>
        assert(body.querySelectorAll("td.total-deployed")(3).textContent == "-")
      }
      assert(TerminalDesksAndQueuesRow.companion(availableOnly, recommended = true, Some(0)) == "-")
      assert(TerminalDesksAndQueuesRow.companion(base, recommended = true, Some(0)) == "0")
    }
    test("saved operational row still allows staff adjustment and retains existing zero defaults") {
      withRow(base.copy(preview = None)) { body =>
        assert(!body.textContent.contains("Not calculated"))
        assert(body.querySelectorAll(".popover-trigger").length == 2)
      }
    }
  }
}
