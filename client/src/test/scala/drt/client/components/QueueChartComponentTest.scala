package drt.client.components

import drt.client.components.TerminalDesksAndQueues.{Deployments, Recommended}
import japgolly.scalajs.react.test.ReactTestUtils
import org.scalajs.dom
import uk.gov.homeoffice.drt.models.CrunchMinute
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import utest._

object QueueChartComponentTest extends TestSuite {
  val queue: Queues.Queue = Queues.EeaDesk
  val testCrunchMinute = CrunchMinute(
    terminal = Terminal("T1"),
    queue = queue,
    minute = 1622548800000L,
    paxLoad = 100.0,
    workLoad = 80.0,
    deskRec = 5,
    waitTime = 10,
    maybePaxInQueue = Some(20),
    deployedDesks = Some(4),
    deployedWait = Some(8),
    maybeDeployedPaxInQueue = Some(15),
    actDesks = Some(3),
    actWait = Some(12),
    lastUpdated = Some(1622548860000L)
  )

  val queueSummaries: List[(Long, Map[Queue, CrunchMinute])] = List.fill(96)((0L, Map(queue -> testCrunchMinute)))
  val baseProps = QueueChartComponent.Props(
    queue = queue,
    queueSummaries = queueSummaries,
    sla = 10,
    interval = 15,
    deskType = Recommended,
    title = Some("Test Chart")
  )

  // these tests will generate warning: ReactDOM.render is no longer supported in React 18
  // it is expected in tests and will be resolved when we update scalajs-react to a newer version
  def tests = Tests {
    test("should mount and render main static elements") {
      val container = dom.document.createElement("div")
      dom.document.body.appendChild(container)
      QueueChartComponent(baseProps).renderIntoDOM(container)

      val titles = container.querySelectorAll("h3")
      assert(titles.length >= 2)

      val controls = container.querySelectorAll(".chart-controls")
      assert(controls.length > 0)

      val legendContainers = container.querySelectorAll(".chart-legend-box")
      assert(legendContainers.length > 0)

      val chartContainers = container.querySelectorAll(".chart-container")
      assert(chartContainers.length > 0)

      dom.document.body.removeChild(container)
    }

    test("should initialise with default visible metrics in state") {
      val deskTypes = List(Recommended, Deployments)
      deskTypes.foreach { deskType =>
        ReactTestUtils.withRenderedIntoDocument(QueueChartComponent(baseProps.copy(deskType = deskType))) { component =>
          val state = component.state
          assert(state.visibleMetrics.contains("Pax in queue"))
          assert(state.visibleMetrics.contains("Incoming pax"))
          assert(state.visibleMetrics.contains(if (deskType == Recommended) "Recommended Staff" else "Staff available"))
          assert(state.visibleMetrics.contains("Wait times"))
          assert(state.visibleMetrics.contains("SLA wait times"))
        }
      }
    }

    test("should render correct chart and key titles from props") {
      val props = baseProps.copy(title = Some("Custom Chart Title"))
      val container = dom.document.createElement("div")
      dom.document.body.appendChild(container)
      QueueChartComponent(props).renderIntoDOM(container)

      val h3s = container.querySelectorAll("h3")
      assert(h3s.length >= 2)
      val chartTitle = h3s(0).textContent
      val keyTitle = h3s(1).textContent
      assert(chartTitle == "Custom Chart Title chart view")
      assert(keyTitle == "Custom Chart Title key for chart")

      dom.document.body.removeChild(container)
    }

    test("should render correct legend labels for Recommended and Deployments deskTypes") {
      val deskTypes = List(Recommended, Deployments)
      val expectedLabelsByDeskType = Map(
        Recommended -> Set(
          "Pax in queue",
          "Incoming pax",
          "Recommended Staff",
          "Wait times",
          "SLA wait times"
        ),
        Deployments -> Set(
          "Pax in queue",
          "Incoming pax",
          "Staff available",
          "Wait times",
          "SLA wait times"
        )
      )

      deskTypes.foreach { deskType =>
        val props = baseProps.copy(deskType = deskType)
        val container = dom.document.createElement("div")
        dom.document.body.appendChild(container)
        QueueChartComponent(props).renderIntoDOM(container)

        val legendContainers = container.querySelectorAll(".chart-legend-box")
        assert(legendContainers.length > 0)
        val legendContainer = legendContainers(0)
        val legendLabels = legendContainer.querySelectorAll("span")

        val expectedLabels = expectedLabelsByDeskType(deskType)
        val actualLabels = (0 until legendLabels.length).map(i => legendLabels(i).textContent.trim).toSet
        assert(expectedLabels.subsetOf(actualLabels))

        dom.document.body.removeChild(container)
      }
    }
  }
}
