package drt.client.components

import diode.UseValueEq
import drt.client.actions.Actions.UpdateStaffAdjustmentDialogueState
import drt.client.components.TerminalDesksAndQueues.{ViewDeps, ViewRecs, ViewType, queueActualsColour, queueColour}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions._
import drt.client.services.{SPACircuit, ViewMode}
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, StaffMinute}
import drt.shared._
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ScalaComponent}
import org.scalajs.dom.html
import org.scalajs.dom.html.TableCell
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.StaffMovementsEdit
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import scala.scalajs.js

object TerminalDesksAndQueuesRow {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  def ragStatus(totalRequired: Int, totalDeployed: Int): String = {
    totalRequired.toDouble / totalDeployed match {
      case diff if diff >= 1 => "red"
      case diff if diff >= 0.75 => "amber"
      case _ => "green"
    }
  }

  case class Props(minuteMillis: MillisSinceEpoch,
                   queueMinutes: Seq[CrunchMinute],
                   staffMinute: StaffMinute,
                   maxPaxInQueues: Map[Queue, Int],
                   airportConfig: AirportConfig,
                   terminal: Terminal,
                   showActuals: Boolean,
                   viewType: ViewType,
                   hasActualDeskStats: Boolean,
                   viewMode: ViewMode,
                   loggedInUser: LoggedInUser,
                   slotMinutes: Int,
                   showWaitColumn: Boolean
                  ) extends UseValueEq

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("TerminalDesksAndQueuesRow")
    .render_P(props => {
      val crunchMinutesByQueue = props.queueMinutes.filter(qm => props.airportConfig.queuesByTerminal(props.terminal).contains(qm.queue)).map(
        qm => Tuple2(qm.queue, qm)).toMap

      val queueTds = crunchMinutesByQueue.flatMap {
        case (queue, cm) =>
          val inQueue: String = cm.maybePaxInQueue.map(Math.round(_).toString).getOrElse("-")
          val widthPct = Math.round(cm.maybePaxInQueue.getOrElse(0).toDouble / props.maxPaxInQueues(queue) * 100).toInt / 2
          val joining = Math.round(cm.paxLoad)
          val paxLoadTd = <.td(^.className := queueColour(queue),
            Tippy.interactive(
              <.div(^.style := js.Dictionary("textAlign" -> "left"),
                s"$joining passengers joining. Maximum passengers in the queue: $inQueue pax in queue"),
              <.div(^.className := "queue-pax",
                <.div(^.className := "queue-pax-joining", joining),
                <.div(^.className := "queue-pax-waiting-graphic",
                  <.div(^.className := "queue-pax-waiting-graphic-bar", ^.style := js.Dictionary("width" -> widthPct))
                )
              ),
            )
          )

          def deployDeskTd(ragClass: String): VdomTagOf[TableCell] = <.td(
            ^.className := s"${queueColour(queue)} $ragClass",
            Tippy.interactive(<.span(s"Suggested deployments with available staff: ${cm.deployedDesks.getOrElse("-")}"),
              s"${cm.deskRec}")
          )

          def deployRecsDeskTd(ragClass: String): VdomTagOf[TableCell] = <.td(
            ^.className := s"${queueColour(queue)} $ragClass",
            Tippy.interactive(
              <.span(s"Recommended for this time slot / queue: ${cm.deskRec}"),
              s"${cm.deployedDesks.getOrElse("-")}"
            )
          )

          val queueCells = props.viewType match {
            case ViewDeps =>
              val ragClass = slaRagStatus(cm.deployedWait.getOrElse(0).toDouble, props.airportConfig.slaByQueue(queue))
              if (props.showWaitColumn)
                List(
                  paxLoadTd,
                  deployRecsDeskTd(ragClass),
                  <.td(
                    ^.className := s"${queueColour(queue)} $ragClass",
                    Tippy.interactive(
                      <.span(s"Recommended for this time slot / queue: ${cm.waitTime}"),
                      s"${cm.deployedWait.map(Math.round(_)).getOrElse("-")}"
                    )
                  )
                )
              else
                List(paxLoadTd, deployRecsDeskTd(ragClass))
            case ViewRecs =>
              val ragClass: String = slaRagStatus(cm.waitTime.toDouble, props.airportConfig.slaByQueue(queue))
              if (props.showWaitColumn)
                List(
                  paxLoadTd,
                  deployDeskTd(ragClass),
                  <.td(
                    ^.className := s"${queueColour(queue)} $ragClass",
                    Tippy.interactive(<.span(s"Suggested deployments with available staff: ${cm.waitTime}"),
                      s"${Math.round(cm.waitTime)}")
                  )
                )
              else
                List(paxLoadTd, deployDeskTd(ragClass))
          }

          def queueActualsTd(actDesks: String) = <.td(^.className := queueActualsColour(queue), actDesks)

          if (props.showActuals) {
            val actDesks: String = cm.actDesks.map(act => s"$act").getOrElse("-")
            val actWaits: String = cm.actWait.map(act => s"$act").getOrElse("-")

            queueCells ++ Seq(queueActualsTd(actDesks), <.td(^.className := queueActualsColour(queue), actWaits))

          } else queueCells
      }
      val fixedPoints = props.staffMinute.fixedPoints
      val movements = props.staffMinute.movements
      val available = props.staffMinute.available
      val crunchMinutes = crunchMinutesByQueue.values.toList
      val totalRequired = DesksAndQueues.totalRequired(props.staffMinute, crunchMinutes)
      val totalDeployed = DesksAndQueues.totalDeployed(props.staffMinute, crunchMinutes)
      val ragClass = ragStatus(totalRequired, available)

      def allowAdjustments: Boolean = props.viewMode.time.millisSinceEpoch > SDate.midnightThisMorning().millisSinceEpoch && props.loggedInUser.hasRole(StaffMovementsEdit)

      val minus: TagMod = adjustmentLink(props, "-")
      val plus: TagMod = adjustmentLink(props, "+")

      val pcpTds: Seq[VdomTagOf[TableCell]] = List(
        <.td(^.className := s"non-pcp", fixedPoints),
        <.td(^.className := s"non-pcp", movements),
        <.td(^.className := s"total-deployed $ragClass", totalRequired),
        <.td(^.className := s"total-deployed", totalDeployed),
        if (allowAdjustments)
          <.td(^.className := s"total-deployed staff-adjustments", ^.colSpan := 2, <.span(minus, <.span(^.className := "deployed", available), plus))
        else
          <.td(^.className := s"total-deployed staff-adjustments", ^.colSpan := 2, <.span(^.className := "deployed", available)))
      <.tr((<.td(SDate(MilliDate(props.minuteMillis)).toHoursAndMinutes) :: queueTds.toList ++ pcpTds).toTagMod)
    })
    .componentDidMount(_ => Callback.log("TerminalDesksAndQueuesRow did mount"))
    .build

  def slaRagStatus(waitTime: Double, sla: Int): String = waitTime / sla match {
    case pc if pc >= 1 => "red"
    case pc if pc >= 0.7 => "amber"
    case _ => ""
  }

  def adjustmentLink(props: Props, action: String): TagOf[html.Div] = {
    val popupState = adjustmentState(props, action)
    val initialiseDialogue = Callback(SPACircuit.dispatch(UpdateStaffAdjustmentDialogueState(Option(popupState))))
    <.div(^.className := "staff-deployment-adjustment-container", <.div(^.className := "popover-trigger", action, ^.onClick --> initialiseDialogue))
  }

  def adjustmentState(props: Props, action: String): StaffAdjustmentDialogueState =
    StaffAdjustmentDialogueState(
      props.airportConfig.terminals,
      Option(props.terminal),
      "Additional info",
      SDate(props.minuteMillis),
      30,
      action,
      1,
      props.loggedInUser
    )

  def apply(props: Props): VdomElement = component(props)
}
