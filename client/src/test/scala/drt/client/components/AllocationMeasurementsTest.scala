package drt.client.components

import diode.data.Ready
import drt.client.SPAMain.{ Loc, TerminalPageTabLoc }
import drt.client.services.{ AllocationPreviewState, ViewDay }
import drt.client.services.AllocationPreviewState.Context
import drt.client.services.JSDateConversions.SDate
import drt.shared.AllocationPreview.{ MeasurementComparison, MeasurementSummary, Measurements, Response, Result }
import drt.shared.FeatureFlags
import drt.shared.airportconfig.Test
import japgolly.scalajs.react.extra.router.Path
import japgolly.scalajs.react.test.MockRouterCtl
import org.scalajs.dom
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{ AccessOnlyPreprod, Debug, DesksAndQueuesView }
import uk.gov.homeoffice.drt.models.UserPreferences
import uk.gov.homeoffice.drt.ports.Queues.{ EeaDesk, NonEeaDesk }
import uk.gov.homeoffice.drt.ports.Terminals.{ T1, T2 }
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import utest._

import scala.collection.immutable.SortedMap

object AllocationMeasurementsTest extends TestSuite {
  private val day = SDate("2026-10-30T00:00Z")
  private val legacy = Measurements(10L, 5000000000L, 30L, 1L)
  private val neutral = Measurements(12L, 4999999999L, 30L, 2L)
  private val legacySummary = MeasurementSummary(
    Map(NonEeaDesk -> legacy, EeaDesk -> Measurements(0L, 0L, 0L, 0L)),
    Some(legacy),
    Seq.empty,
    resourceFeasible = true
  )
  private val neutralSummary = MeasurementSummary(
    Map(NonEeaDesk -> neutral, EeaDesk -> Measurements(0L, 0L, 0L, 0L)),
    Some(neutral),
    Seq.empty,
    resourceFeasible = true
  )
  private val result = Result(
    Seq(NonEeaDesk, EeaDesk),
    Seq.empty,
    Seq.empty,
    Map(NonEeaDesk -> 45, EeaDesk -> 15),
    Some(MeasurementComparison(legacySummary, neutralSummary))
  )
  private val panel = AllocationMeasurementsComponent.Props(
    T1,
    day.toLocalDate,
    "recommended",
    day.addHours(-4).millisSinceEpoch,
    day.addDays(1).addHours(-4).millisSinceEpoch,
    result
  )

  private def withPanel(props: AllocationMeasurementsComponent.Props)(check: dom.Element => Unit): Unit = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    AllocationMeasurementsComponent(props).renderIntoDOM(container)
    try check(container)
    finally dom.document.body.removeChild(container)
  }

  private def withPreviewProps(check: AllocationPreviewComponent.Props => Unit): Unit = {
    val portCode = dom.document.createElement("input")
    portCode.id = "port-code"
    portCode.setAttribute("value", "TEST")
    dom.document.body.appendChild(portCode)
    val user = LoggedInUser("test", "owner", "", Set(AccessOnlyPreprod, Debug, DesksAndQueuesView))
    val location = TerminalPageTabLoc(
      "T1",
      "current",
      "desksAndQueues",
      Map("date" -> day.toLocalDate.toISOString, "viewType" -> "ideal", "displayType" -> "table")
    )
    val saved = TerminalDesksAndQueues.Props(
      router = MockRouterCtl[Loc](pageToPath = loc => Path(loc.url)),
      viewStart = day.addHours(10),
      hoursToView = 1,
      airportConfig = Test.config,
      slaConfigs = Ready(SlaConfigs(SortedMap(day.millisSinceEpoch -> Test.config.slaByQueue))),
      terminalPageTab = location,
      showActuals = true,
      viewMode = ViewDay(day.toLocalDate, None),
      loggedInUser = user,
      featureFlags = FeatureFlags(false, false, false, false, false, enableQueueAllocationPreview = true),
      windowCrunchSummaries = Ready(Map.empty),
      windowStaffRecs = Ready(Map.empty),
      windowStaffDeps = Ready(Map.empty),
      dayCrunchSummaries = Ready(Map.empty),
      windowStaffSummaries = Ready(Map.empty),
      addedStaffMovementMinutes = Map.empty,
      terminal = T1,
      userPreferences = UserPreferences(24, false, false, 15, Map.empty, Map.empty)
    )
    val state = AllocationPreviewState(
      context = Some(Context(T1, day.toLocalDate, eligible = true, ownerId = user.id)),
      selectedKind = "recommended",
      response = Some(Response(panel.startMillis, panel.endMillis, Some(result), None))
    )
    try check(AllocationPreviewComponent.Props(saved, state, portAccess = true))
    finally dom.document.body.removeChild(portCode)
  }

  private def measurementCount(props: AllocationPreviewComponent.Props): Int =
    AllocationPreviewComponent.measurementProps(props).size

  val tests: Tests = Tests {
    test("counts remain Long; signed changes use raw minutes before staff-hour rounding") {
      assert(AllocationMeasurementsComponent.format(5000000000L) == "5000000000")
      assert(AllocationMeasurementsComponent.format(Long.MaxValue) == "9223372036854775807")
      assert(AllocationMeasurementsComponent.change(Long.MaxValue, Long.MaxValue - 1) == "-1")
      assert(AllocationMeasurementsComponent.change(10L, 12L) == "+2")
      assert(AllocationMeasurementsComponent.change(12L, 10L) == "-2")
      assert(AllocationMeasurementsComponent.change(0L, 0L) == "0")
      assert(AllocationMeasurementsComponent.format(1L, staffHours = true) == "0.02")
      assert(AllocationMeasurementsComponent.format(2L, staffHours = true) == "0.03")
      assert(AllocationMeasurementsComponent.format(60L, staffHours = true) == "1.00")
      assert(AllocationMeasurementsComponent.change(1L, 2L, staffHours = true) == "+0.02")
      assert(AllocationMeasurementsComponent.change(2L, 1L, staffHours = true) == "-0.02")
      assert(AllocationMeasurementsComponent.change(0L, 0L, staffHours = true) == "0.00")
    }
    test("four compact cards preserve queue order, full window, zero values and neutral-minus-legacy direction") {
      withPanel(panel) { container =>
        assert(container.querySelectorAll(".allocation-measurements-card").length == 4)
        assert(container.textContent.contains("Recommended measurements: T1, 2026-10-30"))
        assert(container.textContent.contains(SDate(panel.startMillis).toISOString))
        assert(container.textContent.contains(SDate(panel.endMillis).toISOString))
        assert(container.textContent.contains("saved Legacy rows may differ"))
        assert(container.textContent.contains("at or above"))
        assert(container.textContent.contains("One bank counts as one member of staff"))
        val cards = container.querySelectorAll(".allocation-measurements-card")
        val firstRows = cards(0).querySelectorAll("tbody tr")
        assert(firstRows(0).textContent.startsWith(uk.gov.homeoffice.drt.ports.Queues.displayName(NonEeaDesk)))
        assert(firstRows(1).querySelectorAll("td")(0).textContent == "0")
        assert(firstRows(0).querySelectorAll("td")(2).textContent == "+2")
        assert(firstRows(2).textContent.startsWith("Total (queue-minutes)"))
        assert(cards(1).querySelector("tbody tr td").textContent == "5000000000")
        assert(cards(1).querySelectorAll("tbody tr td")(2).textContent == "-1")
        assert(cards(2).querySelectorAll("tbody tr td")(2).textContent == "0")
        assert(cards(3).querySelectorAll("tbody tr td")(2).textContent == "+0.02")
      }
    }
    test("missing queue and total measurements are Unavailable, never zero or numeric differences") {
      val incomplete = legacySummary.copy(
        byQueue = Map(EeaDesk -> legacy),
        total = None,
        unavailableQueues = Seq(NonEeaDesk),
        resourceFeasible = false
      )
      val props =
        panel.copy(result = result.copy(measurements = Some(MeasurementComparison(incomplete, neutralSummary))))
      withPanel(props) { container =>
        assert(container.textContent.contains("Comparison incomplete"))
        val rows = container.querySelector(".allocation-measurements-card").querySelectorAll("tbody tr")
        assert(rows(0).querySelectorAll("td")(0).textContent == "Unavailable")
        assert(rows(0).querySelectorAll("td")(2).textContent == "Unavailable")
        assert(rows(2).querySelectorAll("td")(0).textContent == "Unavailable")
        assert(rows(2).querySelectorAll("td")(2).textContent == "Unavailable")
        assert(!container.textContent.contains("Legacy allocation exceeds"))
      }
    }
    test("resource-limit warning does not remove valid values or claim that staff savings are better") {
      val overbooked = legacySummary.copy(resourceFeasible = false)
      withPanel(panel.copy(result =
        result.copy(measurements =
          Some(MeasurementComparison(overbooked, neutralSummary))
        )
      )) { container =>
        assert(container.textContent.contains("Legacy allocation exceeds the captured resource limits"))
        assert(container.querySelector("tbody tr td").textContent == "10")
        assert(container.querySelectorAll(".green, .red").length == 0)
      }
    }
    test("older results without measurements explain the absence rather than inventing zeroes") {
      withPanel(panel.copy(result = result.copy(measurements = None))) { container =>
        assert(container.textContent.contains("Measurements unavailable for this result"))
        assert(container.querySelectorAll("table").length == 0)
      }
    }
    test("ready measurements are offered under Legacy and Neutral, for only the selected kind and full window") {
      withPreviewProps { props =>
        assert(measurementCount(props) == 1)
        val neutral = props.copy(preview = props.preview.copy(active = true))
        assert(measurementCount(neutral) == 1)
        val savedAvailable = props.copy(saved =
          props.saved.copy(terminalPageTab =
            TerminalPageTabLoc(
              "T1",
              "current",
              "desksAndQueues",
              Map(
                "date" -> day.toLocalDate.toISOString,
                "viewType" -> "deployments",
                "displayType" -> "table"
              )
            )
          )
        )
        assert(measurementCount(savedAvailable) == 0)
        val unavailable = neutral.copy(preview =
          neutral.preview.copy(
            selectedKind = "available",
            pendingKind = Some("available")
          )
        )
        assert(measurementCount(unavailable) == 0)
        val available = result.copy(measurements = Some(MeasurementComparison(neutralSummary, legacySummary)))
        val both = unavailable.copy(preview =
          unavailable.preview.copy(
            pendingKind = None,
            response = props.preview.response.map(_.copy(available = Some(available)))
          )
        )
        val selected = AllocationPreviewComponent.measurementProps(both).get
        assert(selected.kind == "available", selected.result == available)
        assert(selected.startMillis == panel.startMillis, selected.endMillis == panel.endMillis)
        assert(measurementCount(both) == 1)
        withPanel(selected) { container =>
          assert(container.textContent.contains("Available measurements"))
          assert(container.querySelector("tbody tr td").textContent == "12")
        }
      }
    }
    test("permission, owner, terminal, date and Time Machine guards hide even forged ready caches") {
      withPreviewProps { props =>
        val denied = Seq(
          props.copy(portAccess = false),
          props.copy(saved =
            props.saved.copy(featureFlags = props.saved.featureFlags.copy(enableQueueAllocationPreview = false))
          ),
          props.copy(saved =
            props.saved.copy(loggedInUser = props.saved.loggedInUser.copy(roles = Set(DesksAndQueuesView)))
          ),
          props.copy(saved = props.saved.copy(loggedInUser = props.saved.loggedInUser.copy(id = "other-owner"))),
          props.copy(saved = props.saved.copy(terminal = T2)),
          props.copy(saved = props.saved.copy(viewMode = ViewDay(day.addDays(1).toLocalDate, None))),
          props.copy(saved = props.saved.copy(viewMode = ViewDay(day.toLocalDate, Some(day)))),
          props.copy(
            portAccess = false,
            preview = props.preview.copy(context =
              props.preview.context.map(_.copy(eligible = false))
            )
          )
        )
        denied.foreach(p => assert(measurementCount(p) == 0))
      }
    }
  }
}
