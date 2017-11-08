package drt.client.components

import drt.client.components.TerminalDesksAndQueues.{ViewDeps, ViewRecs, ViewType, queueActualsColour, queueColour}
import drt.client.services.JSDateConversions
import drt.shared.CrunchApi.{CrunchMinute, CrunchState, MillisSinceEpoch, StaffMinute}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.html_<^.{<, VdomElement, _}
import japgolly.scalajs.react.{Callback, ReactEventFromInput, ScalaComponent}

object TerminalDesksAndQueuesRow {

  def ragStatus(totalRequired: Int, totalDeployed: Int) = {
    totalRequired.toDouble / totalDeployed match {
      case diff if diff >= 1 => "red"
      case diff if diff >= 0.75 => "amber"
      case _ => ""
    }
  }

  case class Props(minuteMillis: MillisSinceEpoch,
                   queueMinutes: Seq[CrunchMinute],
                   staffMinutes: Seq[StaffMinute],
                   airportConfig: AirportConfig,
                   terminalName: TerminalName,
                   showActuals: Boolean,
                   viewType: ViewType)

  implicit val rowPropsReuse: Reusability[Props] = Reusability.by((props: Props) => {
    (props.queueMinutes.hashCode, props.showActuals, props.viewType.hashCode)
  })

  val component = ScalaComponent.builder[Props]("TerminalDesksAndQueuesRow")
    .render_P((props) => {
      val crunchMinutesByQueue = props.queueMinutes.map(qm => Tuple2(qm.queueName, qm)).toMap
      val queueTds = crunchMinutesByQueue.flatMap {
        case (qn, cm) =>
          val paxLoadTd = <.td(^.className := queueColour(qn), s"${Math.round(cm.paxLoad)}")
          val queueCells = props.viewType match {
            case ViewDeps => List(paxLoadTd,
              <.td(^.className := queueColour(qn), ^.title := s"Rec: ${cm.deskRec}", s"${cm.deployedDesks.getOrElse("-")}"),
              <.td(^.className := queueColour(qn), ^.title := s"With rec: ${cm.waitTime}", s"${cm.deployedWait.map(Math.round(_)).getOrElse("-")}"))
            case ViewRecs => List(paxLoadTd,
              <.td(^.className := queueColour(qn), ^.title := s"Dep: ${cm.deskRec}", s"${cm.deskRec}"),
              <.td(^.className := queueColour(qn), ^.title := s"With Dep: ${cm.waitTime}", s"${Math.round(cm.waitTime)}"))
          }

          if (props.showActuals) {
            val actDesks: String = cm.actDesks.map(act => s"$act").getOrElse("-")
            val actWaits: String = cm.actWait.map(act => s"$act").getOrElse("-")
            queueCells ++ Seq(<.td(^.className := queueActualsColour(qn), actDesks), <.td(^.className := queueActualsColour(qn), actWaits))
          } else queueCells
      }
      val fixedPoints = crunchMinutesByQueue.map(_.).sum
      val totalRequired = crunchMinutesByQueue.map(_._2.deskRec).sum
      val totalDeployed = crunchMinutesByQueue.map(_._2.deployedDesks.getOrElse(0)).sum
      val ragClass = ragStatus(totalRequired, totalDeployed)
      import JSDateConversions._
      val downMovementPopup = StaffDeploymentsAdjustmentPopover(props.airportConfig.terminalNames, Option(props.terminalName), "-", "Staff decrease...", SDate(props.minuteMillis), SDate(props.minuteMillis).addHours(1), "left", "-")()
      val upMovementPopup = StaffDeploymentsAdjustmentPopover(props.airportConfig.terminalNames, Option(props.terminalName), "+", "Staff increase...", SDate(props.minuteMillis), SDate(props.minuteMillis).addHours(1), "left", "+")()

      val pcpTds = List(
        <.td(^.className := s"total-deployed $ragClass", "fp"),
        <.td(^.className := s"total-deployed $ragClass", totalRequired),
        <.td(^.className := s"total-deployed $ragClass", totalDeployed),
        <.td(^.className := s"total-deployed $ragClass staff-adjustments", <.span(downMovementPopup, <.span(^.className := "deployed", "avail"), upMovementPopup)))
      <.tr((<.td(SDate(MilliDate(props.minuteMillis)).toHoursAndMinutes()) :: queueTds.toList ++ pcpTds).toTagMod)
    })
    .componentDidMount((p) => Callback.log("TerminalDesksAndQueuesRow did mount"))
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(props: Props): VdomElement = component(props)
}

object TerminalDesksAndQueues {

  def queueDisplayName(name: String): QueueName = Queues.queueDisplayNames.getOrElse(name, name)

  def queueColour(queueName: String): String = queueName + "-user-desk-rec"

  def queueActualsColour(queueName: String): String = s"${queueColour(queueName)} actuals"

  case class Props(crunchState: CrunchState, airportConfig: AirportConfig, terminalName: TerminalName)

  sealed trait ViewType

  case object ViewRecs extends ViewType

  case object ViewDeps extends ViewType

  case class State(showActuals: Boolean, viewType: ViewType)

  implicit val propsReuse: Reusability[Props] = Reusability.by((props: Props) => {
    val lastUpdatedCm = props.crunchState.crunchMinutes.map(_.lastUpdated)
    val lastUpdatedFs = props.crunchState.flights.map(_.lastUpdated)
    (lastUpdatedCm, lastUpdatedFs)
  })

  implicit val stateReuse: Reusability[State] = Reusability.by((state: State) => {
    state.showActuals
  })

  val component = ScalaComponent.builder[Props]("Loader")
    .initialState[State](State(false, ViewDeps))
    .renderPS((scope, props, state) => {
      def groupCrunchMinutesBy15 = CrunchApi.groupCrunchMinutesByX(15) _
      def groupStaffMinutesBy15 = CrunchApi.groupStaffMinutesByX(15) _

      val queueNames = props.airportConfig.queues(props.terminalName).collect {
        case queueName: String if queueName != Queues.Transfer => queueName
      }

      def deskUnitLabel(queueName: QueueName): String = {
        queueName match {
          case "eGate" => "Banks"
          case _ => "Desks"
        }
      }

      def staffDeploymentSubheadings(queueName: QueueName) = {
        val queueColumnClass = queueColour(queueName)
        val queueColumnActualsClass = queueActualsColour(queueName)
        val headings = state.viewType match {
          case ViewDeps =>
            List(
              <.th(^.title := "Suggested deployment given available staff", s"Dep ${deskUnitLabel(queueName)}", ^.className := queueColumnClass),
              <.th(^.title := "Wait times with suggested deployments", "Est wait", ^.className := queueColumnClass))
          case ViewRecs =>
            List(
              <.th(^.title := "Recommendations to best meet SLAs", s"Rec ${deskUnitLabel(queueName)}", ^.className := queueColumnClass),
              <.th(^.title := "Wait times with recommendations", "Est wait", ^.className := queueColumnClass))
        }

        if (state.showActuals)
          headings ++ List(
            <.th(^.title := "Actual desks used", s"Act ${deskUnitLabel(queueName)}", ^.className := queueColumnActualsClass),
            <.th(^.title := "Actual wait times", "Act wait", ^.className := queueColumnActualsClass))
        else headings
      }

      def subHeadingLevel2(queueNames: Seq[QueueName]) = {
        val queueSubHeadings = queueNames.flatMap(queueName => <.th(^.className := queueColour(queueName), "Pax") :: staffDeploymentSubheadings(queueName)).toTagMod

        List(queueSubHeadings,
          <.th(^.className := "total-deployed", "Misc", ^.title := "Miscellaneous staff"),
          <.th(^.className := "total-deployed", "Rec", ^.title := "Total staff recommended for desks"),
          <.th(^.className := "total-deployed", "Dep", ^.title := "Total staff deployed based on assignments entered"),
          <.th(^.className := "total-deployed", "Avail", ^.title := "Total staff available based on staff entered"))
      }

      val showActsClassSuffix = if (state.showActuals) "-with-actuals" else ""
      val colsClass = s"cols-${queueNames.length}$showActsClassSuffix"

      def qth(queueName: String, xs: TagMod*) = <.th((^.className := queueName + "-user-desk-rec") :: xs.toList: _*)

      val queueHeadings: List[TagMod] = props.airportConfig.queues(props.terminalName).collect {
        case queueName if queueName != Queues.Transfer =>
          val colsToSpan = if (state.showActuals) 5 else 3
          qth(queueName, queueDisplayName(queueName), ^.colSpan := colsToSpan, ^.className := "top-heading")
      }.toList

      val headings: List[TagMod] = queueHeadings :+ <.th(^.className := "total-deployed", ^.colSpan := 4, "PCP")

      val terminalCrunchMinutes = groupCrunchMinutesBy15(
        CrunchApi.terminalMinutesByMinute(props.crunchState.crunchMinutes, props.terminalName),
        props.terminalName,
        Queues.queueOrder
      )
      val terminalStaffMinutes = groupStaffMinutesBy15(
        CrunchApi.terminalMinutesByMinute(props.crunchState.staffMinutes, props.terminalName),
        props.terminalName
      ).toMap

      val toggleShowActuals = (e: ReactEventFromInput) => {
        val newValue: Boolean = e.target.checked
        scope.modState(_.copy(showActuals = newValue))
      }

      def toggleViewType(newViewType: ViewType) = (e: ReactEventFromInput) => {
        scope.modState(_.copy(viewType = newViewType))
      }

      <.div(
        if (props.airportConfig.hasActualDeskStats) {
          <.div(
            <.input.checkbox(^.checked := state.showActuals, ^.onChange ==> toggleShowActuals, ^.id := "show-actuals"),
            <.label(^.`for` := "show-actuals", "Show BlackJack Data"),
            <.input.radio(^.checked := state.viewType == ViewDeps, ^.onChange ==> toggleViewType(ViewDeps), ^.id := "show-deps"),
            <.label(^.`for` := "show-deps", "Available staff deployments"),
            <.input.radio(^.checked := state.viewType == ViewRecs, ^.onChange ==> toggleViewType(ViewRecs), ^.id := "show-recs"),
            <.label(^.`for` := "show-recs", "Recommendations")
          )
        } else "",
        <.table(^.cls := s"table table-striped table-hover table-sm user-desk-recs $colsClass",
          <.thead(
            ^.display := "block",
            <.tr(<.th("") :: headings: _*),
            <.tr(<.th("Time", ^.className := "time") :: subHeadingLevel2(queueNames): _*)),
          <.tbody(
            ^.display := "block",
            ^.overflow := "scroll",
            ^.height := "500px",
            terminalCrunchMinutes.map {
              case (millis, minutes) =>
                val rowProps = TerminalDesksAndQueuesRow.Props(millis, minutes, terminalStaffMinutes(millis), props.airportConfig, props.terminalName, state.showActuals, state.viewType)
                TerminalDesksAndQueuesRow(rowProps)
            }.toTagMod))
      )
    })
    .componentDidMount((p) => Callback.log("TerminalDesksAndQueues did mount"))
    .build

  def apply(props: Props): VdomElement = component(props)
}
