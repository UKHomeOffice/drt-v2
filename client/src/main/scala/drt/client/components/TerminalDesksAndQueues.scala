package drt.client.components

import diode.UseValueEq
import drt.client.SPAMain.{Loc, TerminalPageTabLoc, UrlViewType}
import drt.client.components.ChartJSComponent._
import drt.client.components.TerminalDesksAndQueues.{NodeListSeq, documentScrollHeight, documentScrollTop}
import drt.client.components.ToolTips._
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.ViewMode
import drt.shared.CrunchApi.StaffMinute
import drt.shared._
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{BackendScope, Callback, ReactEventFromInput, ScalaComponent}
import org.scalajs.dom
import org.scalajs.dom.html.{Div, TableCell}
import org.scalajs.dom.raw.Node
import org.scalajs.dom.{DOMList, Element, Event, NodeListOf}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, Queue, Transfer}
import uk.gov.homeoffice.drt.ports.{AirportConfig, Queues}
import uk.gov.homeoffice.drt.time.SDateLike

import scala.util.{Success, Try}


object TerminalDesksAndQueues {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  def queueDisplayName(name: String): String = Queues.displayName(Queue(name))

  def queueColour(queue: Queue): String = queue.toString.toLowerCase + "-user-desk-rec"

  def queueActualsColour(queue: Queue): String = s"${queueColour(queue)} actuals"

  case class Props(router: RouterCtl[Loc],
                   portState: PortState,
                   viewStart: SDateLike,
                   hoursToView: Int,
                   airportConfig: AirportConfig,
                   terminalPageTab: TerminalPageTabLoc,
                   showActuals: Boolean,
                   viewMode: ViewMode,
                   loggedInUser: LoggedInUser,
                   featureFlags: FeatureFlags
                  ) extends UseValueEq

  sealed trait ViewType {
    val queryParamsValue: String
  }

  case object ViewRecs extends ViewType {
    override val queryParamsValue: String = "recs"
  }

  case object ViewDeps extends ViewType {
    override val queryParamsValue: String = "deps"
  }

  case class State(showActuals: Boolean, viewType: ViewType, showWaitColumn: Boolean)

  class Backend(backendScope: BackendScope[Props, State]) {

    def render(props: Props, state: State): VdomTagOf[Div] = {
      val slotMinutes = 15

      def deskUnitLabel(queue: Queue): String = {
        queue match {
          case EGate => "Banks"
          case _ => "Desks"
        }
      }

      val terminal = props.terminalPageTab.terminal

      def queueNames: Seq[Queue] = {
        props.airportConfig.nonTransferQueues(terminal)
      }

      def staffDeploymentSubheadings(queueName: Queue, showWaitColumn: Boolean): List[VdomTagOf[TableCell]] = {
        val queueColumnClass = queueColour(queueName)
        val queueColumnActualsClass = queueActualsColour(queueName)
        val headings = state.viewType match {
          case ViewDeps =>
            val h = List(<.th(
              s"Dep ${deskUnitLabel(queueName)}", " ", depBanksOrDesksTip(queueName), ^.className := queueColumnClass)
            )
            if (showWaitColumn)
              h :+ <.th("Est wait", " ", estWaitTooltip, ^.className := queueColumnClass)
            else
              h
          case ViewRecs =>
            val h = List(<.th(s"Rec ${deskUnitLabel(queueName)} ", recBanksOrDesksTip(queueName), ^.className := queueColumnClass))
            if (showWaitColumn)
              h :+ <.th("Est wait", " ", estWaitTooltip, ^.className := queueColumnClass)
            else
              h
        }

        if (props.airportConfig.hasActualDeskStats && state.showActuals)
          headings ++ List(
            <.th(Tippy.describe(<.span("Actual desks used"), s"Act ${deskUnitLabel(queueName)}"), ^.className := queueColumnActualsClass),
            <.th(Tippy.describe(<.span("Actual wait times"), "Act wait"), ^.className := queueColumnActualsClass))
        else headings
      }

      def subHeadingLevel2(queueNames: Seq[Queue], showWaitColumn: Boolean) = {
        val queueSubHeadings = queueNames.flatMap { queueName =>
          <.th(^.className := queueColour(queueName), "Pax") :: staffDeploymentSubheadings(queueName, showWaitColumn)
        }.toTagMod

        List(queueSubHeadings,
          <.th(^.className := "non-pcp", "Misc", " ", miscTooltip),
          <.th(^.className := "non-pcp", "Moves", " ", movesTooltip),
          <.th(^.className := "total-deployed", "Rec", " ", recToolTip),
          <.th(^.className := "total-deployed", "Dep"),
          <.th(^.className := "total-deployed", "Avail", " ", availTooltip, ^.colSpan := 2))
      }

      def qth(queue: Queue, xs: TagMod*) = <.th((^.className := queue.toString.toLowerCase + "-user-desk-rec") :: xs.toList: _*)

      val queueHeadings: List[TagMod] = queueNames.map(queue => {
        val colsToSpan = (state.showWaitColumn, state.showActuals) match {
          case (true, true) => 5
          case (false, true) => 4
          case (false, false) => 2
          case (_, _) => 3
        }
        qth(queue, queueDisplayName(queue.toString), ^.colSpan := colsToSpan, ^.className := "top-heading")
      }).toList

      val headings: List[TagMod] = queueHeadings ++ List(
        <.th(^.className := "non-pcp", ^.colSpan := 2, ""),
        <.th(^.className := "total-deployed", ^.colSpan := 4, "PCP")
      )

      val queues = props.airportConfig.nonTransferQueues(terminal).toList
      val terminalCrunchMinutes = props.portState.crunchSummary(props.viewStart, props.hoursToView * 4, 15, terminal, queues)
      val terminalStaffMinutes = props.portState.staffSummary(props.viewStart, props.hoursToView * 4, 15, terminal)
      val viewMinutes = props.viewStart.millisSinceEpoch until (props.viewStart.millisSinceEpoch + (props.hoursToView * 60 * 60000)) by 15 * 60000

      val toggleWaitColumn = (e: ReactEventFromInput) => {
        val newValue: Boolean = e.target.checked
        backendScope.modState(_.copy(showWaitColumn = newValue))
      }

      def toggleViewType(newViewType: ViewType) = (_: ReactEventFromInput) => {
        GoogleEventTracker.sendEvent(s"$terminal", "Desks & Queues", newViewType.toString)
        props.router.set(
          props.terminalPageTab.withUrlParameters(UrlViewType(Option(newViewType)))
        )
      }

      def viewTypeControls(displayWaitTimesToggle: Boolean): TagMod = {
        val controls = List(
          <.div(^.className := s"controls-radio-wrapper",
            <.input.radio(^.checked := state.viewType == ViewRecs, ^.onChange ==> toggleViewType(ViewRecs), ^.id := "show-recs"),
            <.label(^.`for` := "show-recs", "Ideal staff", " ", recommendationsTooltip)
          ),
          <.div(^.className := s"controls-radio-wrapper",
            <.input.radio(^.checked := state.viewType == ViewDeps, ^.onChange ==> toggleViewType(ViewDeps), ^.id := "show-deps"),
            <.label(^.`for` := "show-deps", "Available staff", " ", availableStaffDeploymentsTooltip)
          ))

        val allControls = if (displayWaitTimesToggle) controls :+ viewWaitTimeControls else controls

        <.div(^.className := "selector-control2",
          allControls.toTagMod
        )
      }

      def viewWaitTimeControls: TagMod = {
        List(
          <.div(^.className := s"controls-radio-wrapper",
            <.input.checkbox(^.checked := state.showWaitColumn, ^.onChange ==> toggleWaitColumn, ^.id := "toggle-showWaitingTime"),
            <.label(^.`for` := "toggle-showWaitingTime", "Display wait times")
          )).toTagMod
      }

      val dataStickyAttr = VdomAttr("data-sticky") := "data-sticky"

      val classesAttr = ^.cls := s"table table-striped table-hover table-sm user-desk-recs"

      def floatingHeader(showWaitColumn: Boolean) = {
        <.div(^.id := "toStick", ^.className := "container sticky",
          <.table(classesAttr,
            <.thead(
              <.tr(<.th("") :: headings: _*),
              <.tr(<.th("Time", ^.className := "time") :: subHeadingLevel2(queueNames, showWaitColumn): _*)),
            <.tbody()
          ))
      }

      val maxPaxInQueues: Map[Queue, Int] = terminalCrunchMinutes
        .toList
        .flatMap {
          case (minute, queuesAndMinutes) =>
            queuesAndMinutes.map {
              case (queue, cm) => (queue, cm.maybePaxInQueue.getOrElse(0))
            }
        }
        .groupBy(_._1)
        .map {
          case (queue, queueMinutes) => (queue, queueMinutes)
        }
        .mapValues(_.map(_._2).max)

      def queueGraphic(queue: Queue) = {
        val labels: Seq[String] = (0 until 96).map(m => SDate("2022-08-17T23:00").addMinutes(m * 15).toHoursAndMinutes)
        val dayStart = SDate(props.viewStart.getLocalLastMidnight.millisSinceEpoch)
        val sortedCrunchMinuteSummaries = props.portState.crunchSummary(dayStart, 96, 15, terminal, queues).toList.sortBy(_._1)
        val paxInQueueSet: ChartJsDataSet = ChartJsDataSet.line(
          label = "Pax in queue",
          data = sortedCrunchMinuteSummaries.map {
            case (_, queuesAndMinutes) => queuesAndMinutes(queue).maybePaxInQueue.getOrElse(0).toDouble
          },
          colour = RGBA.blue1,
          backgroundColour = Option(RGBA.blue1.copy(alpha = 0.2)),
          pointRadius = Option(0),
          yAxisID = Option("y"),
          fill = Option(true),
        )
        val incomingPax: ChartJsDataSet = ChartJsDataSet.line(
          label = "Incoming pax",
          data = sortedCrunchMinuteSummaries.map {
            case (_, queuesAndMinutes) => queuesAndMinutes(queue).paxLoad
          },
          colour = RGBA.blue2.copy(alpha = 0.4),
          pointRadius = Option(0),
          yAxisID = Option("y"),
        )
        val desks: ChartJsDataSet = ChartJsDataSet.bar(
          label = "Staff",
          data = sortedCrunchMinuteSummaries.map {
            case (_, queuesAndMinutes) => queuesAndMinutes(queue).deskRec.toDouble
          },
          colour = RGBA(200, 200, 200),
          backgroundColour = Option(RGBA(200, 200, 200, 0.2)),
          yAxisID = Option("y2"),
        )
        val waits: ChartJsDataSet = ChartJsDataSet.line(
          label = "Wait times",
          data = sortedCrunchMinuteSummaries.map {
            case (_, queuesAndMinutes) => queuesAndMinutes(queue).waitTime.toDouble
          },
          colour = RGBA.red1,
          backgroundColour = Option(RGBA.red1.copy(alpha = 0.2)),
          pointRadius = Option(0),
          yAxisID = Option("y3"),
          fill = Option(true),
        )
        val sla: ChartJsDataSet = ChartJsDataSet.line(
          label = "SLA",
          data = Seq.fill(96)(props.airportConfig.slaByQueue(queue).toDouble),
          colour = RGBA.green3,
          pointRadius = Option(0),
          yAxisID = Option("y3"),
        )
        ChartJSComponent(
          ChartJsProps(
            data = ChartJsData(
              datasets = Seq(paxInQueueSet, incomingPax, desks, waits, sla),
              labels = Option(labels),
            ),
            width = 300,
            height = 25,
            options = ChartJsOptions.withMultipleDataSets(queue.toString, suggestedMax = Map("y3" -> props.airportConfig.slaByQueue(queue) * 2), maxTicks = 96)
          )
        )
      }

      <.div(
        floatingHeader(state.showWaitColumn),
        props.airportConfig.queuesByTerminal(props.terminalPageTab.terminal).filterNot(_ == Transfer).map(queueGraphic).toTagMod,
        <.div(^.className := "desks-and-queues-top",
          viewTypeControls(props.featureFlags.displayWaitTimesToggle),
          StaffMissingWarningComponent(terminalStaffMinutes, props.loggedInUser, props.router, props.terminalPageTab)
        ),
        <.table(
          ^.id := "sticky",
          classesAttr,
          <.thead(
            dataStickyAttr,
            <.tr(<.th("") :: headings: _*),
            <.tr(<.th("Time", ^.className := "time") :: subHeadingLevel2(queueNames, state.showWaitColumn): _*)),
          <.tbody(
            ^.id := "sticky-body",
            viewMinutes.map { millis =>
              val rowProps = TerminalDesksAndQueuesRow.Props(
                minuteMillis = millis,
                queueMinutes = queues.map(q => terminalCrunchMinutes(millis)(q)),
                staffMinute = terminalStaffMinutes.getOrElse(millis, StaffMinute.empty),
                maxPaxInQueues = maxPaxInQueues,
                airportConfig = props.airportConfig,
                terminal = terminal,
                showActuals = state.showActuals,
                viewType = state.viewType,
                hasActualDeskStats = props.airportConfig.hasActualDeskStats,
                viewMode = props.viewMode,
                loggedInUser = props.loggedInUser,
                slotMinutes = slotMinutes,
                showWaitColumn = state.showWaitColumn
              )
              TerminalDesksAndQueuesRow(rowProps)
            }.toTagMod))
      )
    }


  }

  val component = ScalaComponent.builder[Props]("Loader")
    .initialStateFromProps(p => State(showActuals = p.airportConfig.hasActualDeskStats && p.showActuals, p.terminalPageTab.viewType, showWaitColumn = !p.featureFlags.displayWaitTimesToggle))
    .renderBackend[Backend]
    .componentDidMount(_ => StickyTableHeader("[data-sticky]"))
    .build

  def documentScrollTop: Double = Math.max(dom.document.documentElement.scrollTop, dom.document.body.scrollTop)

  def documentScrollHeight: Double = Math.max(dom.document.documentElement.scrollHeight, dom.document.body.scrollHeight)

  def apply(props: Props): VdomElement = component(props)

  implicit class NodeListSeq[T <: Node](nodes: DOMList[T]) extends IndexedSeq[T] {
    override def foreach[U](f: T => U): Unit = {
      for (i <- 0 until nodes.length) {
        f(nodes(i))
      }
    }

    override def length: Int = nodes.length

    override def apply(idx: Int): T = nodes(idx)
  }

}

object StickyTableHeader {
  def toIntOrElse(intString: String, stickyInitial: Int): Int = {
    Try {
      intString.toDouble.round.toInt
    } match {
      case Success(x) => x
      case _ => stickyInitial
    }
  }

  def handleStickyClass(top: Double,
                        bottom: Double,
                        elements: NodeListSeq[Element],
                        toStick: Element): Unit = {
    elements.foreach(sticky => {
      val stickyEnter = toIntOrElse(sticky.getAttribute("data-sticky-initial"), 0)
      val stickyExit = bottom.round.toInt

      if (top >= stickyEnter && top <= stickyExit)
        toStick.classList.add("sticky-show")
      else toStick.classList.remove("sticky-show")
    })
  }

  def setInitialHeights(elements: NodeListSeq[Element]): Unit = {
    elements.foreach(element => {
      val scrollTop = documentScrollTop
      val relativeTop = element.getBoundingClientRect().top
      val actualTop = relativeTop + scrollTop
      element.setAttribute("data-sticky-initial", actualTop.toString)
    })
  }

  def apply(selector: String): Callback = {

    val stickies: NodeListSeq[Element] = dom.document.querySelectorAll(selector).asInstanceOf[NodeListOf[Element]]

    dom.document.addEventListener("scroll", (_: Event) => {
      val top = documentScrollTop
      val bottom = documentScrollHeight
      Option(dom.document.querySelector("#sticky-body")).foreach { _ =>
        handleStickyClass(top, bottom, stickies, dom.document.querySelector("#toStick"))
      }
    })

    Callback(setInitialHeights(stickies))
  }
}
