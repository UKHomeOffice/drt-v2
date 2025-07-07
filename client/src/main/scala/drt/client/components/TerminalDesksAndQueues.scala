package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.SPAMain.{Loc, TerminalPageTabLoc, UrlDisplayType, UrlViewType}
import drt.client.actions.Actions.{RequestDateDeskRecsRecalculation, RequestDatePaxLoadsRecalculation}
import drt.client.components.ToolTips._
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.handlers.UpdateUserPreferences
import drt.client.services.{SPACircuit, StaffMovementMinute, ViewMode}
import drt.shared.CrunchApi.StaffMinute
import drt.shared._
import io.kinoplan.scalajs.react.material.ui.core.{MuiButton, MuiTypography}
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.RefreshOutlined
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, ScalaComponent}
import org.scalajs.dom.{DOMList, Node}
import org.scalajs.dom.html.{Div, TableCell}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.SuperAdmin
import uk.gov.homeoffice.drt.models.{CrunchMinute, UserPreferences}
import uk.gov.homeoffice.drt.ports.Queues.{EGate, Queue, Transfer}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.ports.{AirportConfig, Queues}
import uk.gov.homeoffice.drt.time.SDateLike

import scala.scalajs.js

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
                   windowCrunchSummaries: Pot[Map[Long, Map[Queue, CrunchMinute]]],
                   dayCrunchSummaries: Pot[Map[Long, Map[Queue, CrunchMinute]]],
                   windowStaffSummaries: Pot[Map[Long, StaffMinute]],
                   addedStaffMovementMinutes: Map[TM, Seq[StaffMovementMinute]],
                   terminal: Terminal,
                   userPreferences: UserPreferences
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

  sealed trait TimeInterval {
    val queryParamsValue: String
  }

  case object Hourly extends TimeInterval {
    override val queryParamsValue: String = "hourly"
  }

  case object Quarterly extends TimeInterval {
    override val queryParamsValue: String = "quarterly"
  }

  case class State(showActuals: Boolean, deskType: DeskType, displayType: DisplayType, timeInterval: TimeInterval, showWaitColumn: Boolean) extends UseValueEq

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
            <.th(Tippy.describe("actual-desks-used", <.span("Actual desks used"), s"Act ${deskUnitLabel(queueName)}"), ^.className := queueColumnActualsClass),
            <.th(Tippy.describe("actual-wait-times", <.span("Actual wait times"), "Act wait"), ^.className := queueColumnActualsClass))
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

       def handleTimeInterval = (e: ReactEventFromInput, timeInterval: TimeInterval) =>  Callback{
        e.preventDefault()
         GoogleEventTracker.sendEvent(s"$terminal", s"Select ${timeInterval.queryParamsValue} interval", timeInterval.toString)
         SPACircuit.dispatch(UpdateUserPreferences(props.userPreferences.copy(desksAndQueuesIntervalMinutes = if (timeInterval == Hourly) 60 else 15)))
       }


      def toggleDisplayType(newDisplayType: DisplayType) = (e: ReactEventFromInput) => {
        e.preventDefault()
        GoogleEventTracker.sendEvent(s"$terminal", "Select display type", newDisplayType.toString)
        props.router.set(
          props.terminalPageTab.withUrlParameters(UrlDisplayType(Option(newDisplayType)))
        )
      }

      def requestDeskRecsRecalculation(): Callback = Callback {
        SPACircuit.dispatch(RequestDateDeskRecsRecalculation(props.viewStart.toLocalDate))
      }

      def requestPaxLoadsRecalculation(): Callback = Callback {
        SPACircuit.dispatch(RequestDatePaxLoadsRecalculation(props.viewStart.toLocalDate))
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


        val displayIntervalControls = List(
          <.div(^.className := s"controls-radio-wrapper",
            <.input.radio(^.checked := state.timeInterval == Quarterly, ^.onChange ==> ((e: ReactEventFromInput) => handleTimeInterval(e, Quarterly)), ^.id := "display-quaterly-interval"),
            <.label(^.`for` := "display-quaterly-interval", "Every 15 minutes")
          ),
          <.div(^.className := s"controls-radio-wrapper",
            <.input.radio(^.checked := state.timeInterval == Hourly, ^.onChange ==> ((e: ReactEventFromInput) => handleTimeInterval(e, Hourly)), ^.id := "display-hourly-interval"),
            <.label(^.`for` := "display-hourly-interval", "Hourly")
          ))

        <.div(^.className := "view-controls",
          <.div(^.className := "view-controls-label", "Staff:", <.div(^.className := "view-controls-selector", deskTypeControls.toTagMod)),
          <.span(^.className := "separator"),
          <.div(^.className := "view-controls-label", "Format:", <.div(^.className := "view-controls-selector", displayTypeControls.toTagMod)),
          <.span(^.className := "separator"),
          <.div(^.className := "view-controls-label", "Intervals:", <.div(^.className := "view-controls-selector", displayIntervalControls.toTagMod)),
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
          val interval = if (state.timeInterval == Hourly) 60 else 15
          val viewMinutes = props.viewStart.millisSinceEpoch until (props.viewStart.millisSinceEpoch + (props.hoursToView * 60 * 60000)) by interval * 60000

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

          <.div(^.className := "desks-queues-title",
            MuiTypography(variant = "h2")(s"Desks and queues at ${props.terminalPageTab.portCodeStr} (${props.airportConfig.portName}), ${props.terminalPageTab.terminal}"),
            StaffMissingWarningComponent(windowStaffMinutes, props.loggedInUser, props.router, props.terminalPageTab),
            <.div(^.className := "desks-and-queues-top",
              viewTypeControls(props.featureFlags.displayWaitTimesToggle),
              if (props.loggedInUser.hasRole(SuperAdmin)) <.div(^.style := js.Dynamic.literal("display" -> "flex", "flexDirection" -> "row"),
                adminRecalcButton(requestDeskRecsRecalculation, "Recalc Desk Recs"),
                adminRecalcButton(requestPaxLoadsRecalculation, "Recalc Pax Loads"),
              )
              else EmptyVdom,
            ),
            if (state.displayType == ChartsView) {
              props.airportConfig.queuesByTerminal(props.terminalPageTab.terminal).filterNot(_ == Transfer).map { queue =>
                val sortedCrunchMinuteSummaries = dayCrunchMinutes.toList.sortBy(_._1)
                QueueChartComponent(QueueChartComponent.Props(queue, sortedCrunchMinuteSummaries, slas(queue), interval, state.deskType))
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
                    viewMinutes.map { millis =>
                      val rowProps = TerminalDesksAndQueuesRow.Props(
                        minuteMillis = millis,
                        queueMinutes = queues.map(q => windowCrunchMinutes(millis)(q)),
                        staffMinute = staffMinutesWithLocalUpdates(props.addedStaffMovementMinutes, windowStaffMinutes.getOrElse(millis, StaffMinute.empty)),
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

  def staffMinutesWithLocalUpdates(addedStaffMovementMinutes: Map[TM, Seq[StaffMovementMinute]], staffMinute: StaffMinute): StaffMinute =
    addedStaffMovementMinutes
      .getOrElse(TM(staffMinute), Seq.empty[StaffMovementMinute])
      .filter(_.createdAt > staffMinute.lastUpdated.getOrElse(0L))
      .foldLeft(staffMinute)((sm, movementMinute) => sm.copy(movements = sm.movements + movementMinute.staff))

  private def adminRecalcButton(requestRecalc: () => Callback, label: String): VdomTagOf[Div] = {
    <.div(^.className := "re-crunch", MuiButton(
      variant = "outlined",
      size = "medium",
      color = Color.primary,
    )(MuiIcons(RefreshOutlined)(fontSize = "large"),
      ^.onClick --> requestRecalc(),
      label)
    )
  }

  val component: Component[Props, State, Backend, CtorType.Props] = ScalaComponent.builder[Props]("Loader")
    .initialStateFromProps { p =>
      State(
        showActuals = p.airportConfig.hasActualDeskStats && p.showActuals,
        deskType = p.terminalPageTab.deskType,
        displayType = p.terminalPageTab.displayAs,
        timeInterval = p.userPreferences.desksAndQueuesIntervalMinutes match {
          case 60 => Hourly
          case _ => Quarterly
        },
        showWaitColumn = !p.featureFlags.displayWaitTimesToggle)
    }
    .renderBackend[Backend]
    .build

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
