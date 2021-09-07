package drt.client.components

import diode.UseValueEq
import drt.client.actions.Actions.UpdateStaffAdjustmentDialogueState
import drt.client.components.TerminalDesksAndQueues.{ViewDeps, ViewRecs, ViewType, queueActualsColour, queueColour}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions._
import drt.client.services.{SPACircuit, ViewMode}
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, StaffMinute}
import drt.shared.Terminals.Terminal
import drt.shared._
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ScalaComponent}
import org.scalajs.dom.html
import org.scalajs.dom.html.TableCell
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.StaffMovementsEdit

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

//  implicit val propsReuse: Reusability[Props] = Reusability.by(p =>
//    (p.queueMinutes.hashCode(), p.staffMinute.hashCode(), p.showActuals, p.viewType.hashCode(), p.viewMode.hashCode(), p.showWaitColumn)
//  )

  val component = ScalaComponent.builder[Props]("TerminalDesksAndQueuesRow")
    .render_P(props => {
      val crunchMinutesByQueue = props.queueMinutes.filter(qm => props.airportConfig.queuesByTerminal(props.terminal).contains(qm.queue)).map(
        qm => Tuple2(qm.queue, qm)).toMap

      val queueTds = crunchMinutesByQueue.flatMap {
        case (qn, cm) =>
          val paxLoadTd = <.td(^.className := queueColour(qn), s"${Math.round(cm.paxLoad)}")

          def deployDeskTd(ragClass: String) = <.td(
            ^.className := s"${queueColour(qn)} $ragClass",
            Tippy.interactive(<.span(s"Suggested deployments with available staff: ${cm.deployedDesks.getOrElse("-")}"),
              s"${cm.deskRec}")
          )

          def deployRecsDeskTd(ragClass: String) = <.td(
            ^.className := s"${queueColour(qn)} $ragClass",
            Tippy.interactive(
              <.span(s"Recommended for this time slot / queue: ${cm.deskRec}"),
              s"${cm.deployedDesks.getOrElse("-")}"
            )
          )

          val queueCells = props.viewType match {
            case ViewDeps =>
              val ragClass = cm.deployedWait.getOrElse(0).toDouble / props.airportConfig.slaByQueue(qn) match {
                case pc if pc >= 1 => "red"
                case pc if pc >= 0.7 => "amber"
                case _ => ""
              }
              if (props.showWaitColumn)
                List(
                  paxLoadTd,
                  deployRecsDeskTd(ragClass),
                  <.td(
                    ^.className := s"${queueColour(qn)} $ragClass",
                    Tippy.interactive(
                      <.span(s"Recommended for this time slot / queue: ${cm.waitTime}"),
                      s"${cm.deployedWait.map(Math.round(_)).getOrElse("-")}"
                    )
                  )
                )
              else
                List(paxLoadTd, deployRecsDeskTd(ragClass))
            case ViewRecs =>
              val ragClass: String = slaRagStatus(cm.waitTime.toDouble, props.airportConfig.slaByQueue(qn))
              if (props.showWaitColumn)
                List(
                  paxLoadTd,
                  deployDeskTd(ragClass),
                  <.td(
                    ^.className := s"${queueColour(qn)} $ragClass",
                    Tippy.interactive(<.span(s"Suggested deployments with available staff: ${cm.waitTime}"),
                      s"${Math.round(cm.waitTime)}")
                  )
                )
              else
                List(paxLoadTd, deployDeskTd(ragClass))
          }

          def queueActualsTd(actDesks: String) = <.td(^.className := queueActualsColour(qn), actDesks)

          if (props.showActuals) {
            val actDesks: String = cm.actDesks.map(act => s"$act").getOrElse("-")
            val actWaits: String = cm.actWait.map(act => s"$act").getOrElse("-")

            queueCells ++ Seq(queueActualsTd(actDesks), <.td(^.className := queueActualsColour(qn), actWaits))

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
//    .configure(Reusability.shouldComponentUpdate)
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
