package drt.client.components

import diode.{ Action, ActionProcessor, ActionResult, Dispatcher }
import diode.data.Ready
import drt.client.SPAMain.{ Loc, TerminalPageTabLoc }
import drt.client.actions.Actions.{ RecordClientSideStaffMovement, UpdateShowActualDesksAndQueues }
import drt.client.actions.AllocationPreviewActions._
import drt.client.services.AllocationPreviewState.Context
import drt.client.services.JSDateConversions.SDate
import drt.client.services.handlers.SetUserPreferences
import drt.client.services.{ AllocationPreviewState, RootModel, SPACircuit, ViewDay }
import drt.shared.AllocationPreview.{ Response, Result }
import drt.shared.CrunchApi.StaffMinute
import drt.shared.{ FeatureFlags, PortState }
import drt.shared.airportconfig.Test
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.extra.router.Path
import japgolly.scalajs.react.test.{ MockRouterCtl, ReactTestUtils, Simulate }
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{ AccessOnlyPreprod, Debug, DesksAndQueuesView }
import uk.gov.homeoffice.drt.models.{ CrunchMinute, UserPreferences }
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.Queues.NonEeaDesk
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.LocalDate
import utest._

import scala.collection.immutable.SortedMap
import scala.collection.mutable.ListBuffer

object AllocationPreviewIntegrationTest extends TestSuite {
  private case class ReplaceRoot(model: RootModel) extends Action
  private val empty = ScalaComponent.builder[Unit]("EmptyPreviewFixture").renderStatic(<.div()).build
  private val user = LoggedInUser("test", "test-owner", "", Set(AccessOnlyPreprod, Debug, DesksAndQueuesView))
  private val preferences = UserPreferences(24, false, false, 15, Map.empty, Map.empty)

  private class FixtureProcessor extends ActionProcessor[RootModel] {
    val requests: ListBuffer[(Long, String)] = ListBuffer.empty

    override def process(
        dispatch: Dispatcher,
        action: Any,
        next: Any => ActionResult[RootModel],
        model: RootModel
    ): ActionResult[RootModel] = action match {
      case ReplaceRoot(replacement) => ActionResult.ModelUpdate(replacement)
      case preview: PreviewAction   =>
        val state = AllocationPreviewState.transition(model.allocationPreview, preview)
        if (state.pending && state.generation != model.allocationPreview.generation)
          requests += ((state.generation, state.pendingKind.get))
        if (state == model.allocationPreview) ActionResult.NoChange
        else ActionResult.ModelUpdate(model.copy(allocationPreview = state))
      case _: RecordClientSideStaffMovement | _: SetUserPreferences | _: UpdateShowActualDesksAndQueues => next(action)
      case _                                                                                            => ActionResult.NoChange
    }
  }

  private def fixture(date: LocalDate, status: String, interval: Int, offsetMinutes: Int): RootModel = {
    val day = SDate(date)
    val capturedStart = day.addMinutes(-offsetMinutes)
    val capturedEnd = day.addDays(1).addMinutes(-offsetMinutes)
    val minuteCount = ((capturedEnd.millisSinceEpoch - capturedStart.millisSinceEpoch) / 60000L).toInt
    val result = Result(
      Seq(EeaDesk),
      (0 until minuteCount).map(i =>
        CrunchMinute(T1, EeaDesk, capturedStart.addMinutes(i).millisSinceEpoch, 1, 2, 3, 16, Some(12))
      ),
      (0 until minuteCount).map(i => StaffMinute(T1, capturedStart.addMinutes(i).millisSinceEpoch, 8, 1, 2, None)),
      Map(EeaDesk -> 15)
    )
    val response = Response(
      capturedStart.millisSinceEpoch,
      capturedEnd.millisSinceEpoch,
      Some(result),
      Some(result.copy(minutes =
        result.minutes.map(_.copy(
          deployedDesks = Some(2),
          deployedWait = Some(7),
          maybeDeployedPaxInQueue = Some(17)
        ))
      ))
    )
    RootModel(
      portStatePot = Ready(PortState.empty),
      airportConfig = Ready(Test.config),
      loggedInUserPot = Ready(user),
      userHasPortAccess = Ready(true),
      featureFlags = Ready(FeatureFlags(false, false, false, false, false, enableQueueAllocationPreview = true)),
      redListUpdates = Ready(RedListUpdates(Map.empty)),
      userPreferences = Ready(preferences.copy(desksAndQueuesIntervalMinutes = interval)),
      shifts = Ready(Seq.empty),
      slaConfigs = Ready(SlaConfigs(SortedMap(day.millisSinceEpoch -> Test.config.slaByQueue))),
      viewMode = ViewDay(date, None),
      allocationPreview = AllocationPreviewState(
        context = Some(Context(T1, date, eligible = true, ownerId = user.id)),
        active = true,
        response = if (status == "ready") Some(response) else None,
        selectedKind = "available",
        pendingKind = if (status == "calculating") Some("available") else None,
        attemptedKinds = if (status == "calculating") Set("available") else Set("recommended", "available")
      )
    )
  }

  private def terminalLocation(
      date: LocalDate,
      subMode: String = "desksAndQueues",
      kind: String = "deployments",
      display: String = "table"
  ): TerminalPageTabLoc =
    TerminalPageTabLoc(
      "T1",
      "current",
      subMode,
      Map(
        "date" -> date.toISOString,
        "viewType" -> kind,
        "displayType" -> display,
        "timeRangeStart" -> "00:00",
        "timeRangeEnd" -> "00:00 +1"
      )
    )

  private def withTerminal(
      date: LocalDate,
      status: String = "ready",
      interval: Int = 15,
      offsetMinutes: Int = 0,
      previewActive: Boolean = true,
      previewEnabled: Boolean = true,
      timeMachine: Boolean = false
  )(
      check: (dom.Element, MockRouterCtl[Loc], FixtureProcessor) => Unit
  ): Unit = {
    val existingPortCode = Option(dom.document.getElementById("port-code"))
    val portCode = existingPortCode.getOrElse(dom.document.createElement("input"))
    val previousPortCode = Option(portCode.getAttribute("value"))
    portCode.id = "port-code"
    portCode.setAttribute("value", "TEST")
    if (existingPortCode.isEmpty) dom.document.body.appendChild(portCode)
    val previous = SPACircuit.zoom(identity).value
    val processor = new FixtureProcessor
    val router = MockRouterCtl[Loc](pageToPath = location => Path(location.url))
    val location = terminalLocation(date)
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    SPACircuit.addProcessor(processor)
    val base = fixture(date, status, interval, offsetMinutes)
    val initial = base.copy(
      featureFlags = base.featureFlags.map(_.copy(enableQueueAllocationPreview = previewEnabled)),
      viewMode = ViewDay(date, Option.when(timeMachine)(SDate(date)))
    )
    SPACircuit.dispatch(ReplaceRoot(if (previewActive) initial
    else initial.copy(allocationPreview = AllocationPreviewState())))
    try {
      TerminalComponent(TerminalComponent.Props(location, router)).renderIntoDOM(container)
      check(container, router, processor)
    } finally {
      empty().renderIntoDOM(container)
      SPACircuit.dispatch(ReplaceRoot(previous))
      SPACircuit.removeProcessor(processor)
      dom.document.body.removeChild(container)
      if (existingPortCode.isEmpty) dom.document.body.removeChild(portCode)
      else previousPortCode match {
        case Some(value) => portCode.setAttribute("value", value)
        case None        => portCode.removeAttribute("value")
      }
    }
  }

  private def rerender(container: dom.Element, router: MockRouterCtl[Loc], location: TerminalPageTabLoc): Unit =
    TerminalComponent(TerminalComponent.Props(location, router)).renderIntoDOM(container)

  private def rows(container: dom.Element): Int = container.querySelectorAll("table.user-desk-recs tbody tr").length

  private def checkedValue(container: dom.Element, name: String): String =
    container.querySelector(s"input[name='$name']:checked").getAttribute("value")

  private def churnPreferences(): Unit = (1 to 3).foreach { index =>
    SPACircuit.dispatch(SetUserPreferences(preferences.copy(hidePaxDataSourceDescription = index % 2 == 0)))
  }

  private def toggleAllocation(container: dom.Element, neutral: Boolean): Unit = {
    val input = container.querySelector("input[name='allocation-mode']").asInstanceOf[dom.html.Input]
    input.checked = neutral
    ReactTestUtils.act(Simulate.change(input))
  }

  private def dropQueueAndStaffMinutes(date: LocalDate): Unit = {
    val root = SPACircuit.zoom(identity).value
    val preview = root.allocationPreview
    val day = SDate(date)
    val original = preview.response.get.recommended.get
    val incomplete = original.copy(
      queues = Seq(EeaDesk, NonEeaDesk),
      minutes = (original.minutes ++ original.minutes.map(_.copy(queue = NonEeaDesk)))
        .filterNot(m => m.queue == NonEeaDesk && m.minute == day.addMinutes(20).millisSinceEpoch),
      staffMinutes = original.staffMinutes.filterNot(_.minute == day.addMinutes(40).millisSinceEpoch),
      slas = Map(EeaDesk -> 15, NonEeaDesk -> 45)
    )
    val response = preview.response.map(_.copy(recommended = Some(incomplete), available = Some(incomplete)))
    SPACircuit.dispatch(ReplaceRoot(root.copy(allocationPreview = preview.copy(response = response))))
  }

  val tests: Tests = Tests {
    test("mounted saved actuals and wait columns follow current props without remounting") {
      withTerminal(LocalDate(2026, 10, 30), previewActive = false) { (container, _, _) =>
        val root = SPACircuit.zoom(identity).value
        SPACircuit.dispatch(ReplaceRoot(root.copy(airportConfig = Ready(Test.config.copy(hasActualDeskStats = true)))))
        val table = container.querySelector("table.user-desk-recs")
        assert(container.querySelectorAll(".actuals").length == 0)
        SPACircuit.dispatch(UpdateShowActualDesksAndQueues(true))
        assert(container.querySelectorAll(".actuals").length > 0)
        assert(container.querySelector("table.user-desk-recs") == table)
        SPACircuit.dispatch(UpdateShowActualDesksAndQueues(false))
        assert(container.querySelectorAll(".actuals").length == 0)
        assert(container.querySelector("table.user-desk-recs") == table)
        val row = container.querySelector("table.user-desk-recs tbody tr")
        val originalCells = row.children.length
        val current = SPACircuit.zoom(identity).value
        SPACircuit.dispatch(ReplaceRoot(current.copy(featureFlags =
          current.featureFlags.map(_.copy(displayWaitTimesToggle = true))
        )))
        assert(row.children.length == originalCells - Test.config.queueTypeSplitOrder(T1).size)
        assert(container.querySelector("table.user-desk-recs") == table)
        SPACircuit.dispatch(ReplaceRoot(current))
        assert(row.children.length == originalCells)
      }
    }
    test("preview hides saved actuals and restores the unchanged actuals setting on exit") {
      val date = LocalDate(2026, 10, 30)
      withTerminal(date, previewActive = false) { (container, _, _) =>
        val root = SPACircuit.zoom(identity).value
        SPACircuit.dispatch(ReplaceRoot(root.copy(airportConfig = Ready(Test.config.copy(hasActualDeskStats = true)))))
        SPACircuit.dispatch(UpdateShowActualDesksAndQueues(true))
        assert(container.querySelectorAll(".actuals").length > 0)
        val saved = SPACircuit.zoom(identity).value
        val ancestor = container.querySelector(".queues-and-arrivals")
        SPACircuit.dispatch(ReplaceRoot(saved.copy(allocationPreview =
          fixture(date, "ready", 15, 0).allocationPreview
        )))
        assert(container.querySelectorAll(".actuals").length == 0)
        assert(SPACircuit.zoom(_.showActualIfAvailable).value)
        assert(container.querySelector(".queues-and-arrivals") == ancestor)
        toggleAllocation(container, neutral = false)
        assert(container.querySelectorAll(".actuals").length > 0)
        assert(SPACircuit.zoom(_.showActualIfAvailable).value)
      }
    }
    test("mounted saved Chart kind switch keeps one staff checkbox and canvas, checked and then explicitly hidden") {
      val date = LocalDate(2026, 10, 30)
      withTerminal(date, previewActive = false) { (container, router, _) =>
        def showKind(kind: String): dom.html.Input = {
          ReactTestUtils.act(rerender(container, router, terminalLocation(date, kind = kind, display = "charts")))
          container.querySelector("input[data-chart-metric='staff']").asInstanceOf[dom.html.Input]
        }
        val staff = showKind("ideal")
        val canvas = container.querySelector(".chart-container canvas")
        assert(staff.checked)
        def checkAcrossKinds(checked: Boolean): Unit = Seq("deployments", "ideal").foreach { kind =>
          val current = showKind(kind)
          assert(current == staff, current.checked == checked)
          assert(container.querySelector(".chart-container canvas") == canvas)
          assert(current.parentElement.textContent.contains(if (kind == "ideal") "Recommended Staff"
          else "Staff available"))
        }
        checkAcrossKinds(checked = true)
        staff.checked = false
        ReactTestUtils.act(Simulate.change(staff))
        checkAcrossKinds(checked = false)
      }
    }
    test("mounted saved terminal follows current route props without remounting ancestors") {
      val date = LocalDate(2026, 10, 30)
      withTerminal(date, previewActive = false) { (container, router, _) =>
        val ancestor = container.querySelector(".queues-and-arrivals")
        rerender(container, router, terminalLocation(date, subMode = "arrivals"))
        assert(container.querySelector("#arrivals").classList.contains("active"))
        assert(!container.querySelector("#desksAndQueues").classList.contains("active"))
        rerender(container, router, terminalLocation(date, subMode = "staffing"))
        assert(container.querySelector("#available-staff").classList.contains("active"))
        assert(!container.querySelector("#arrivals").classList.contains("active"))
        assert(container.querySelector(".queues-and-arrivals") == ancestor)
        rerender(container, router, terminalLocation(date, kind = "ideal"))
        assert(checkedValue(container, "deskType") == "recommended")
        rerender(container, router, terminalLocation(date, kind = "ideal", display = "charts"))
        assert(container.querySelectorAll(".chart-container").length == Test.config.queueTypeSplitOrder(T1).size)
        assert(checkedValue(container, "displayType") == "charts")
        rerender(container, router, terminalLocation(date))
        assert(checkedValue(container, "deskType") == "deployments")
        assert(checkedValue(container, "displayType") == "table")
        assert(container.querySelectorAll("table.user-desk-recs").length == 1)
        assert(container.querySelector(".queues-and-arrivals") == ancestor)
      }
    }
    test("mounted saved interval radios and rows follow updated preferences consistently") {
      withTerminal(LocalDate(2026, 10, 30), previewActive = false) { (container, _, _) =>
        assert(rows(container) == 96)
        SPACircuit.dispatch(SetUserPreferences(preferences.copy(desksAndQueuesIntervalMinutes = 60)))
        assert(checkedValue(container, "displayInterval") == "hourly")
        assert(rows(container) == 24)
        SPACircuit.dispatch(SetUserPreferences(preferences))
        assert(checkedValue(container, "displayInterval") == "quarterly")
        assert(rows(container) == 96)
      }
    }
    test("mounted preview table and chart show only captured complete slots, incl. DST days and crunch offsets") {
      val cases = Seq(
        (LocalDate(2026, 3, 29), 0, false, Map(15 -> (92, "23:45"), 60 -> (23, "23:00"))),
        (LocalDate(2026, 10, 25), 0, false, Map(15 -> (100, "23:45"), 60 -> (25, "23:00"))),
        (LocalDate(2026, 10, 30), 240, false, Map(15 -> (80, "19:45"), 60 -> (20, "19:00"))),
        (LocalDate(2026, 10, 30), -30, false, Map(15 -> (94, "23:45"), 60 -> (23, "23:00"))),
        (LocalDate(2026, 10, 30), 0, true, Map(15 -> (94, "23:45"), 60 -> (23, "23:00")))
      )
      for {
        (date, offset, incomplete, expected) <- cases
        (interval, (expectedRows, lastSlot)) <- expected
      } withTerminal(date, interval = interval, offsetMinutes = offset) { (container, _, processor) =>
        if (incomplete) dropQueueAndStaffMinutes(date)
        assert(rows(container) == expectedRows)
        assert(container.querySelector("table.user-desk-recs tbody tr:last-child td").textContent == lastSlot)
        Simulate.change(container.querySelector("input[name='displayType'][value='charts']"))
        val queues = SPACircuit.zoom(_.allocationPreview).value.response.get.recommended.get.queues
        assert(container.querySelectorAll(".chart-container").length == queues.size)
        assert(processor.requests.isEmpty)
      }
    }
    test(
      "mounted terminal preserves ready and pending personal preview across normal staffing and preference updates"
    ) {
      Seq("ready", "calculating").foreach { status =>
        withTerminal(LocalDate(2026, 10, 30), status) { (container, _, processor) =>
          val before = SPACircuit.zoom(_.allocationPreview).value
          val panel = container.querySelector("input[name='allocation-mode']")
          assert(Option(panel).nonEmpty)
          SPACircuit.dispatch(RecordClientSideStaffMovement(
            T1,
            SDate(before.context.get.date).millisSinceEpoch,
            SDate(before.context.get.date).addMinutes(15).millisSinceEpoch,
            4
          ))
          SPACircuit.dispatch(SetUserPreferences(preferences.copy(hidePaxDataSourceDescription = true)))
          assert(container.querySelector("input[name='allocation-mode']") == panel)
          assert(SPACircuit.zoom(_.allocationPreview).value == before)
          assert(processor.requests.isEmpty)
        }
      }
    }
    test("mounted table chart switches are local, do not calculate or dispose and retain saved kind after exit") {
      withTerminal(LocalDate(2026, 10, 30)) { (container, router, processor) =>
        val before = SPACircuit.zoom(_.allocationPreview).value
        Simulate.change(container.querySelector("input[name='displayType'][value='charts']"))
        assert(container.querySelectorAll(".chart-container").length == 1)
        Simulate.change(container.querySelector("input[name='displayType'][value='table']"))
        assert(container.querySelectorAll("table.user-desk-recs").length == 1)
        Simulate.change(container.querySelector("input[name='deskType'][value='recommended']"))
        assert(checkedValue(container, "deskType") == "recommended")
        Simulate.change(container.querySelector("input[name='displayInterval'][value='hourly']"))
        assert(rows(container) == 24)
        assert(SPACircuit.zoom(_.userPreferences).value.get.desksAndQueuesIntervalMinutes == 15)
        assert(router.events().isEmpty, processor.requests.isEmpty)
        assert(SPACircuit.zoom(_.allocationPreview).value == before.copy(selectedKind = "recommended"))
        toggleAllocation(container, neutral = false)
        assert(!SPACircuit.zoom(_.allocationPreview).value.active)
        assert(checkedValue(container, "deskType") == "deployments")
        assert(router.events().isEmpty)
      }
    }
    test("Legacy stays visible during one preparation; the ready toggle handles mouse and native changes instantly") {
      withTerminal(LocalDate(2026, 10, 30), previewActive = false) { (container, _, processor) =>
        val toggle = container.querySelector("input[name='allocation-mode']").asInstanceOf[dom.html.Input]
        val savedTable = container.querySelector("table.user-desk-recs")
        assert(!toggle.checked, toggle.disabled, toggle.`type` == "checkbox")
        assert(toggle.getAttribute("role") == "switch")
        assert(toggle.getAttribute("aria-label") == "Use Neutral allocation")
        assert(container.querySelectorAll("input[type='radio'][name='allocation-mode']").length == 0)
        ReactTestUtils.act(toggle.click())
        assert(!toggle.checked)
        assert(processor.requests.size == 1)
        assert(processor.requests.head._2 == "available")
        val controls = container.querySelector(".allocation-mode-controls")
        assert(controls != null)
        assert(controls.querySelector("[role='status']").textContent == "Preparing...")
        assert(container.querySelector("table.user-desk-recs") == savedTable)
        val generation = SPACircuit.zoom(_.allocationPreview).value.generation
        churnPreferences()
        assert(processor.requests.size == 1)
        val response = fixture(LocalDate(2026, 10, 30), "ready", 15, 0).allocationPreview.response.get
          .copy(recommended = None)
        SPACircuit.dispatch(PreviewReceived(generation, response))
        assert(!toggle.checked, !toggle.disabled)
        assert(controls.querySelector("[role='status']").textContent == "Ready")
        assert(!controls.textContent.contains("NeutralNeutral"))
        assert(container.querySelector("table.user-desk-recs") == savedTable)
        ReactTestUtils.act(toggle.click())
        assert(toggle.checked, processor.requests.size == 1)
        assert(SPACircuit.zoom(_.allocationPreview).value.generation == generation)
        toggleAllocation(container, neutral = false)
        assert(container.querySelector("input[name='allocation-mode']") == toggle, !toggle.checked)
        toggleAllocation(container, neutral = true)
        assert(toggle.checked, processor.requests.size == 1)
        assert(container.querySelectorAll("table.user-desk-recs").length == 1)
      }
    }
    test("failed preparation keeps Legacy visible and requires explicit Retry, not ordinary updates") {
      withTerminal(LocalDate(2026, 10, 30), previewActive = false) { (container, _, processor) =>
        val prepared = SPACircuit.zoom(_.allocationPreview).value
        SPACircuit.dispatch(PreviewRequestFailed(prepared.generation, 404))
        assert(container.textContent.contains("endpoint was not found"))
        assert(container.querySelectorAll("table.user-desk-recs").length == 1)
        val toggle = container.querySelector("input[name='allocation-mode']").asInstanceOf[dom.html.Input]
        assert(toggle.disabled, !toggle.checked)
        churnPreferences()
        assert(processor.requests.size == 1)
        val retry = container.querySelector(".allocation-mode-controls button.allocation-mode-retry")
        assert(retry != null, retry.textContent == "Retry")
        assert(retry.classList.contains("govuk-button--secondary"))
        Simulate.click(retry)
        assert(processor.requests.size == 2)
        assert(!SPACircuit.zoom(_.allocationPreview).value.active)
        assert(container.querySelectorAll("table.user-desk-recs").length == 1)
      }
    }
    test("ineligible and Time Machine views do not prepare Neutral") {
      Seq(false -> false, true -> true).foreach { case (enabled, timeMachine) =>
        withTerminal(
          LocalDate(2026, 10, 30),
          previewActive = false,
          previewEnabled = enabled,
          timeMachine = timeMachine
        ) { (container, _, processor) =>
          assert(processor.requests.isEmpty)
          assert(container.querySelectorAll("input[name='allocation-mode']").length == 0)
          assert(container.querySelectorAll("table.user-desk-recs").length == 1)
        }
      }
    }
    test("first selection of another staffing kind requests only that kind and then retains both locally") {
      withTerminal(LocalDate(2026, 10, 30)) { (container, router, processor) =>
        val root = SPACircuit.zoom(identity).value
        val response = root.allocationPreview.response.get
        SPACircuit.dispatch(ReplaceRoot(root.copy(allocationPreview =
          root.allocationPreview.copy(
            response = Some(response.copy(recommended = None))
          )
        )))
        assert(container.querySelectorAll("table.user-desk-recs tbody tr td.total-deployed")(2).textContent == "-")
        Simulate.change(container.querySelector("input[name='deskType'][value='recommended']"))
        assert(processor.requests.size == 1, processor.requests.head._2 == "recommended")
        assert(container.querySelectorAll("table.user-desk-recs").length == 0)
        val pending = SPACircuit.zoom(_.allocationPreview).value
        SPACircuit.dispatch(SelectNeutralAllocation(neutral = true, kind = "available"))
        assert(container.querySelectorAll("table.user-desk-recs").length == 1)
        val toggle = container.querySelector("input[name='allocation-mode']").asInstanceOf[dom.html.Input]
        assert(!toggle.disabled)
        toggleAllocation(container, neutral = false)
        toggleAllocation(container, neutral = true)
        assert(container.querySelectorAll("table.user-desk-recs").length == 1)
        assert(processor.requests.size == 1)
        SPACircuit.dispatch(drt.client.actions.AllocationPreviewActions.PreviewReceived(
          pending.generation,
          response.copy(available = None)
        ))
        assert(container.querySelectorAll("table.user-desk-recs").length == 1)
        val loaded = SPACircuit.zoom(_.allocationPreview).value
        assert(loaded.response.get.recommended.nonEmpty, loaded.response.get.available.nonEmpty)
        Simulate.change(container.querySelector("input[name='deskType'][value='deployments']"))
        assert(processor.requests.size == 1, router.events().isEmpty)
        assert(SPACircuit.zoom(_.userPreferences).value == root.userPreferences)
      }
    }
  }
}
