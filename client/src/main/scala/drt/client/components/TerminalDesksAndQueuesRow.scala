package drt.client.components

import drt.client.components.StaffDeploymentsAdjustmentPopover.{StaffDeploymentAdjustmentPopoverProps, StaffDeploymentAdjustmentPopoverState}
import drt.client.components.TerminalDesksAndQueues.{ViewDeps, ViewRecs, ViewType, queueActualsColour, queueColour}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions._
import drt.client.services.ViewMode
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, StaffMinute}
import drt.shared.FlightsApi.TerminalName
import drt.shared._
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.{TagOf, html_<^}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ScalaComponent}
import org.scalajs.dom.html

object TerminalDesksAndQueuesRow {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  def ragStatus(totalRequired: Int, totalDeployed: Int): String = {
    totalRequired.toDouble / totalDeployed match {
      case diff if diff >= 1 => "red"
      case diff if diff >= 0.75 => "amber"
      case _ => ""
    }
  }

  case class Props(minuteMillis: MillisSinceEpoch,
                   queueMinutes: Seq[CrunchMinute],
                   staffMinute: StaffMinute,
                   airportConfig: AirportConfig,
                   terminalName: TerminalName,
                   showActuals: Boolean,
                   viewType: ViewType,
                   hasActualDeskStats: Boolean,
                   viewMode: ViewMode,
                   loggedInUser: LoggedInUser,
                   slotMinutes: Int,
                   maybeStaffAdjustmentState: Option[StaffDeploymentAdjustmentPopoverState],
                   updateStaffAdjustmentState: Option[StaffDeploymentAdjustmentPopoverState] => Callback
                  )

  implicit val propsReuse: Reusability[Props] = Reusability.by(p => {
    log.info(s"calcing propsReuse (${p.maybeStaffAdjustmentState.isDefined})")
    (p.queueMinutes.hashCode(), p.staffMinute.hashCode(), p.showActuals, p.viewType.hashCode(), p.viewMode.hashCode(), p.maybeStaffAdjustmentState.hashCode())
  })

  val component = ScalaComponent.builder[Props]("TerminalDesksAndQueuesRow")
    .render_P((props) => {
      val crunchMinutesByQueue = props.queueMinutes.filter(qm => props.airportConfig.queues(props.terminalName).contains(qm.queueName)).map(
        qm => Tuple2(qm.queueName, qm)).toMap
      val queueTds = crunchMinutesByQueue.flatMap {
        case (qn, cm) =>
          val paxLoadTd = <.td(^.className := queueColour(qn), s"${Math.round(cm.paxLoad)}")
          val queueCells = props.viewType match {
            case ViewDeps =>
              val ragClass = cm.deployedWait.getOrElse(0).toDouble / props.airportConfig.slaByQueue(qn) match {
                case pc if pc >= 1 => "red"
                case pc if pc >= 0.7 => "amber"
                case _ => ""
              }
              List(paxLoadTd,
                <.td(^.className := queueColour(qn), ^.title := s"Rec: ${cm.deskRec}", s"${cm.deployedDesks.getOrElse("-")}"),
                <.td(^.className := s"${queueColour(qn)} $ragClass", ^.title := s"With rec: ${cm.waitTime}", s"${cm.deployedWait.map(Math.round(_)).getOrElse("-")}"))
            case ViewRecs =>
              val ragClass = cm.waitTime.toDouble / props.airportConfig.slaByQueue(qn) match {
                case pc if pc >= 1 => "red"
                case pc if pc >= 0.7 => "amber"
                case _ => ""
              }
              List(paxLoadTd,
                <.td(^.className := queueColour(qn), ^.title := s"Dep: ${cm.deployedDesks.getOrElse("-")}", s"${cm.deskRec}"),
                <.td(^.className := s"${queueColour(qn)} $ragClass", ^.title := s"With Dep: ${cm.waitTime}", s"${Math.round(cm.waitTime)}"))
          }

          if (props.showActuals) {
            val actDesks: String = cm.actDesks.map(act => s"$act").getOrElse("-")
            val actWaits: String = cm.actWait.map(act => s"$act").getOrElse("-")
            queueCells ++ Seq(<.td(^.className := queueActualsColour(qn), actDesks), <.td(^.className := queueActualsColour(qn), actWaits))
          } else queueCells
      }
      val fixedPoints = props.staffMinute.fixedPoints
      val movements = props.staffMinute.movements
      val available = props.staffMinute.available
      val crunchMinutes = crunchMinutesByQueue.values.toSet
      val totalRequired = DesksAndQueues.totalRequired(props.staffMinute, crunchMinutes)
      val totalDeployed = DesksAndQueues.totalDeployed(props.staffMinute, crunchMinutes)
      val ragClass = ragStatus(totalRequired, available)
      val slotStart = SDate(props.minuteMillis)
      val slotEnd = slotStart.addMinutes(props.slotMinutes - 1)

      def allowAdjustments: Boolean = props.viewMode.time.millisSinceEpoch > SDate.midnightThisMorning().millisSinceEpoch

      val minus: TagMod = adjustmentLinkWithPopup(props, slotStart, slotEnd, "-", "decrease")
      val plus: TagMod = adjustmentLink(props, "+", "increase", None)

      val pcpTds = List(
        <.td(^.className := s"non-pcp", fixedPoints),
        <.td(^.className := s"non-pcp", movements),
        <.td(^.className := s"total-deployed $ragClass", totalRequired),
        <.td(^.className := s"total-deployed", totalDeployed),
        if (allowAdjustments)
          <.td(^.className := s"total-deployed staff-adjustments", ^.colSpan := 2, <.span(minus, <.span(^.className := "deployed", available), plus))
        else
          <.td(^.className := s"total-deployed staff-adjustments", ^.colSpan := 2, <.span(^.className := "deployed", available)))


      <.tr((<.td(SDate(MilliDate(props.minuteMillis)).toHoursAndMinutes()) :: queueTds.toList ++ pcpTds).toTagMod)
    })
    .componentDidMount(_ => Callback.log("TerminalDesksAndQueuesRow did mount"))
    .configure(Reusability.shouldComponentUpdate)
    .build

  def adjustmentLinkWithPopup(props: Props, slotStart: SDateLike, slotEnd: SDateLike, action: String, label: String): TagMod = props.maybeStaffAdjustmentState match {
    case Some(state) if state.active && state.isApplicableToSlot(slotStart, slotEnd) =>
      val popup: TagMod = StaffDeploymentsAdjustmentPopover(state)(StaffDeploymentAdjustmentPopoverProps(_ => Option(state)))
      adjustmentLink(props, action, label, Option(popup))
    case _ =>
      adjustmentLink(props, action, label, None)
  }

  def adjustmentLink(props: Props, action: String, label: String, maybePopup: Option[TagMod]): TagOf[html.Div] = maybePopup match {
    case Some(popup) =>
      <.div(popup, ^.className := "staff-deployment-adjustment-container", <.div(^.className := "popover-trigger", action))
    case _ =>
      val popupState = adjustmentState(props, action, label)
      <.div(^.className := "staff-deployment-adjustment-container", <.div(^.className := "popover-trigger", action, ^.onClick --> props.updateStaffAdjustmentState(Option(popupState))))
  }

  def adjustmentState(props: Props, action: String, label: String): StaffDeploymentAdjustmentPopoverState = {
    val numStaff = action match {
      case "-" => -1
      case "+" => 1
      case _ => 0
    }
    StaffDeploymentsAdjustmentPopover.StaffDeploymentAdjustmentPopoverState(props.airportConfig.terminalNames, Option(props.terminalName), action, "Staff " + label + "...", SDate(props.minuteMillis), SDate(props.minuteMillis).addHours(1), "left", action, numStaff, props.loggedInUser)
  }

  def apply(props: Props): VdomElement = component(props)
}
