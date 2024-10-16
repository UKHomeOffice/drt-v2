package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.SPAMain.{Loc, TerminalPageTabLoc, UrlDisplayType, UrlViewType}
import drt.client.actions.Actions.RequestDateRecrunch
import drt.client.components.ToolTips._
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.{SPACircuit, ViewMode}
import drt.shared.CrunchApi.StaffMinute
import drt.shared._
import io.kinoplan.scalajs.react.material.ui.core.MuiButton
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.RefreshOutlined
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, ScalaComponent}
import org.scalajs.dom
import org.scalajs.dom.DOMList
import org.scalajs.dom.html.{Div, TableCell}
import org.scalajs.dom.raw.Node
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.SuperAdmin
import uk.gov.homeoffice.drt.ports.Queues.{EGate, Queue, Transfer}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.ports.{AirportConfig, Queues}
import uk.gov.homeoffice.drt.time.SDateLike


object TerminalDesksAndQueues {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  def queueDisplayName(name: String): String = Queues.displayName(Queue(name))

  def queueColour(queue: Queue): String = queue.toString.toLowerCase + "-user-desk-rec"

  def queueActualsColour(queue: Queue): String = s"${queueColour(queue)} actuals"

  case class Props(router: RouterCtl[Loc],
                   viewStart: SDateLike,
                   hoursToView: Int,
                   airportConfig: AirportConfig,
                   slaConfigs: Pot[SlaConfigs],
                   terminalPageTab: TerminalPageTabLoc,
                   showActuals: Boolean,
                   viewMode: ViewMode,
                   loggedInUser: LoggedInUser,
                   featureFlags: FeatureFlags,
                   windowCrunchSummaries: Pot[Map[Long, Map[Queue, CrunchApi.CrunchMinute]]],
                   dayCrunchSummaries: Pot[Map[Long, Map[Queue, CrunchApi.CrunchMinute]]],
                   windowStaffSummaries: Pot[Map[Long, StaffMinute]],
                   terminal: Terminal,
                  ) extends UseValueEq

  sealed trait DeskType {
    val queryParamsValue: String
  }

  case object Ideal extends DeskType {
    override val queryParamsValue: String = "ideal"
  }

  case object Deployments extends DeskType {
    override val queryParamsValue: String = "deployments"
  }

  sealed trait DisplayType {
    val queryParamsValue: String
  }

  case object TableView extends DisplayType {
    override val queryParamsValue: String = "table"
  }

  case object ChartsView extends DisplayType {
    override val queryParamsValue: String = "charts"
  }

  case class State(showActuals: Boolean, deskType: DeskType, displayType: DisplayType, showWaitColumn: Boolean) extends UseValueEq

  class Backend() {
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
        val headings = state.deskType match {
          case Deployments =>
            val h = List(<.th(
              <.div(s"Dep ${deskUnitLabel(queueName)}", depBanksOrDesksTip(queueName)), ^.className := queueColumnClass)
            )
            if (showWaitColumn)
              h :+ <.th(<.div("Est wait", estWaitTooltip), ^.className := queueColumnClass)
            else
              h
          case Ideal =>
            val h = List(<.th(s"Rec ${deskUnitLabel(queueName)} ", recBanksOrDesksTip(queueName), ^.className := queueColumnClass))
            if (showWaitColumn)
              h :+ <.th(<.div("Est wait", " ", estWaitTooltip), ^.className := queueColumnClass)
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
          <.th(^.className := queueColour(queueName), "Incoming pax") :: staffDeploymentSubheadings(queueName, showWaitColumn)
        }.toTagMod

        List(queueSubHeadings,
          <.th(^.className := "non-pcp", <.div("Misc", miscTooltip)),
          <.th(^.className := "non-pcp", <.div("Moves", movesTooltip)),
          <.th(^.className := "total-deployed", <.div("Rec", recToolTip)),
          <.th(^.className := "total-deployed", "Dep"),
          <.th(^.className := "total-deployed", <.div("Avail", availTooltip), ^.colSpan := 2))
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

      def toggleDeskType(newDeskType: DeskType) = (e: ReactEventFromInput) => {
        e.preventDefault()
        GoogleEventTracker.sendEvent(s"$terminal", "Select desk type", newDeskType.toString)
        props.router.set(
          props.terminalPageTab.withUrlParameters(UrlViewType(Option(newDeskType)))
        )
      }

      def toggleDisplayType(newDisplayType: DisplayType) = (e: ReactEventFromInput) => {
        e.preventDefault()
        GoogleEventTracker.sendEvent(s"$terminal", "Select display type", newDisplayType.toString)
        props.router.set(
          props.terminalPageTab.withUrlParameters(UrlDisplayType(Option(newDisplayType)))
        )
      }

      def requestForecastRecrunch(): Callback = Callback {
        SPACircuit.dispatch(RequestDateRecrunch(props.viewStart.toLocalDate))
      }

      def viewTypeControls(displayWaitTimesToggle: Boolean): TagMod = {
        val deskTypeControls = List(
          <.div(^.className := s"controls-radio-wrapper",
            <.input.radio(^.checked := state.deskType == Ideal, ^.onChange ==> toggleDeskType(Ideal), ^.id := "show-recs"),
            <.label(^.`for` := "show-recs", "Ideal staff", " ", recommendationsTooltip)
          ),
          <.div(^.className := s"controls-radio-wrapper",
            <.input.radio(^.checked := state.deskType == Deployments, ^.onChange ==> toggleDeskType(Deployments), ^.id := "show-deps"),
            <.label(^.`for` := "show-deps", "Available staff", " ", availableStaffDeploymentsTooltip)
          ))

        val displayTypeControls = List(
          <.div(^.className := s"controls-radio-wrapper",
            <.input.radio(^.checked := state.displayType == TableView, ^.onChange ==> toggleDisplayType(TableView), ^.id := "display-table"),
            <.label(^.`for` := "display-table", "Table view")
          ),
          <.div(^.className := s"controls-radio-wrapper",
            <.input.radio(^.checked := state.displayType == ChartsView, ^.onChange ==> toggleDisplayType(ChartsView), ^.id := "display-charts"),
            <.label(^.`for` := "display-charts", "Charts view")
          ))

        <.div(^.className := "view-controls",
          <.div(^.className := "view-controls-selector", deskTypeControls.toTagMod),
          <.div(^.className := "view-controls-selector", displayTypeControls.toTagMod),
        )
      }

      <.div {
        val renderPot = for {
          windowCrunchMinutes <- props.windowCrunchSummaries
          windowStaffMinutes <- props.windowStaffSummaries
          dayCrunchMinutes <- props.dayCrunchSummaries
          slaConfigs <- props.slaConfigs
        } yield {
          val slas = slaConfigs.configForDate(props.viewStart.millisSinceEpoch).getOrElse(props.airportConfig.slaByQueue)
          val queues = props.airportConfig.nonTransferQueues(terminal).toList
          val viewMinutes = props.viewStart.millisSinceEpoch until (props.viewStart.millisSinceEpoch + (props.hoursToView * 60 * 60000)) by 15 * 60000

          val maxPaxInQueues: Map[Queue, Int] = windowCrunchMinutes
            .toList
            .flatMap {
              case (_, queuesAndMinutes) =>
                queuesAndMinutes.map {
                  case (queue, cm) => (queue, cm.maybePaxInQueue.getOrElse(0))
                }
            }
            .groupBy(_._1)
            .map {
              case (queue, queueMinutes) => (queue, queueMinutes)
            }
            .view.mapValues(_.map(_._2).max).toMap

          <.div(
            <.div(^.className := "desks-and-queues-top",
              viewTypeControls(props.featureFlags.displayWaitTimesToggle),
              if (props.loggedInUser.hasRole(SuperAdmin)) adminRecrunchButton(requestForecastRecrunch _) else EmptyVdom,
              StaffMissingWarningComponent(windowStaffMinutes, props.loggedInUser, props.router, props.terminalPageTab)
            ),
            if (state.displayType == ChartsView) {
              props.airportConfig.queuesByTerminal(props.terminalPageTab.terminal).filterNot(_ == Transfer).map { queue =>
                val sortedCrunchMinuteSummaries = dayCrunchMinutes.toList.sortBy(_._1)
                QueueChartComponent(QueueChartComponent.Props(queue, sortedCrunchMinuteSummaries, slas(queue), state.deskType))
              }.toTagMod
            } else {
              <.div(
                <.table(
                  ^.className := s"user-desk-recs table-striped",
                  <.thead(
                    ^.className := "sticky-top",
                    <.tr(<.th(^.className := "solid-background", "") :: headings: _*),
                    <.tr(<.th("Time", ^.className := "solid-background") :: subHeadingLevel2(queueNames, state.showWaitColumn): _*)),
                  <.tbody(
                    ^.id := "sticky-body",
                    viewMinutes.map { millis =>
                      val rowProps = TerminalDesksAndQueuesRow.Props(
                        minuteMillis = millis,
                        queueMinutes = queues.map(q => windowCrunchMinutes(millis)(q)),
                        staffMinute = windowStaffMinutes.getOrElse(millis, StaffMinute.empty),
                        maxPaxInQueues = maxPaxInQueues,
                        airportConfig = props.airportConfig,
                        slaConfigs = props.slaConfigs,
                        terminal = terminal,
                        showActuals = state.showActuals,
                        viewType = state.deskType,
                        hasActualDeskStats = props.airportConfig.hasActualDeskStats,
                        viewMode = props.viewMode,
                        loggedInUser = props.loggedInUser,
                        slotMinutes = slotMinutes,
                        showWaitColumn = state.showWaitColumn
                      )
                      TerminalDesksAndQueuesRow(rowProps)
                    }.toTagMod)
                )
              )
            }
          )
        }
        renderPot.getOrElse(<.div())
      }
    }
  }

  private def adminRecrunchButton(requestForecastRecrunch: () => Callback): VdomTagOf[Div] = {
    <.div(MuiButton(
      variant = "outlined",
      size = "medium",
      color = Color.primary
    )(MuiIcons(RefreshOutlined)(),
      ^.onClick --> requestForecastRecrunch(),
      "Request re-crunch")
    )
  }

  val component: Component[Props, State, Backend, CtorType.Props] = ScalaComponent.builder[Props]("Loader")
    .initialStateFromProps { p =>
      State(
        showActuals = p.airportConfig.hasActualDeskStats && p.showActuals,
        deskType = p.terminalPageTab.deskType,
        displayType = p.terminalPageTab.displayAs,
        showWaitColumn = !p.featureFlags.displayWaitTimesToggle)
    }
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
