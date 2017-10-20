package drt.client.components

import drt.client.components.TerminalDesksAndQueues.{queueActualsColour, queueColour}
import drt.client.services.JSDateConversions
import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.{CrunchMinute, CrunchState, MillisSinceEpoch}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.html_<^.{<, VdomElement, _}
import japgolly.scalajs.react.{Callback, ReactEventFromInput, ScalaComponent}

import scala.scalajs.js.Date

object TerminalDesksAndQueuesRow {

  case class Props(minuteMillis: MillisSinceEpoch, queueMinutes: List[CrunchMinute], airportConfig: AirportConfig, terminalName: TerminalName, showActuals: Boolean)

  implicit val rowPropsReuse: Reusability[Props] = Reusability.by((props: Props) => {
    (props.queueMinutes.hashCode, props.showActuals)
  })

  val component = ScalaComponent.builder[Props]("TerminalDesksAndQueuesRow")
    .render_P((props) => {
      val crunchMinutesByQueue = props.queueMinutes.map(qm => Tuple2(qm.queueName, qm)).toMap
      val queueTds = crunchMinutesByQueue.flatMap {
        case (qn, cm) =>
          val queueCells = List(
            <.td(^.className := queueColour(qn), s"${Math.round(cm.paxLoad)}"),
            <.td(^.className := queueColour(qn), ^.title := s"Rec: ${cm.deskRec}", s"${cm.deployedDesks.map(Math.round(_)).getOrElse("-")}"),
            <.td(^.className := queueColour(qn), ^.title := s"With Rec: ${cm.waitTime}", s"${cm.deployedWait.map(Math.round(_)).getOrElse("-")}")
          )
          if (props.showActuals) {
            val actDesks: String = cm.actDesks.map(act => s"$act").getOrElse("-")
            val actWaits: String = cm.actWait.map(act => s"$act").getOrElse("-")

            queueCells ++ Seq(<.td(^.className := queueActualsColour(qn), actDesks), <.td(^.className := queueActualsColour(qn), actWaits))
          }
          else queueCells
      }
      val totalRequired = crunchMinutesByQueue.map(_._2.deskRec).sum
      val totalDeployed = crunchMinutesByQueue.map(_._2.deployedDesks.getOrElse(0)).sum
      val ragClass = totalRequired.toDouble / totalDeployed match {
        case diff if diff >= 1 => "red"
        case diff if diff >= 0.75 => "amber"
        case _ => ""
      }
      import JSDateConversions._
      val downMovementPopup = StaffDeploymentsAdjustmentPopover(props.airportConfig.terminalNames, Option(props.terminalName), "-", "Staff decrease...", SDate(props.minuteMillis), SDate(props.minuteMillis).addHours(1), "left", "-")()
      val upMovementPopup = StaffDeploymentsAdjustmentPopover(props.airportConfig.terminalNames, Option(props.terminalName), "+", "Staff increase...", SDate(props.minuteMillis), SDate(props.minuteMillis).addHours(1), "left", "+")()

      val pcpTds = List(<.td(^.className := s"total-deployed $ragClass", totalRequired),
        <.td(^.className := s"total-deployed $ragClass staff-adjustments", <.span(downMovementPopup, <.span(^.className := "deployed", totalDeployed), upMovementPopup)))
      <.tr((<.td(SDate(MilliDate(props.minuteMillis)).toHoursAndMinutes()) :: queueTds.toList ++ pcpTds).toTagMod)
    })
    .componentDidMount((p) => Callback.log("TerminalDesksAndQueuesRow did mount"))
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(props: Props): VdomElement = component(props)
}

object TerminalDesksAndQueues {
  val queueDisplayNames = Map(Queues.EeaDesk -> "EEA", Queues.NonEeaDesk -> "Non-EEA", Queues.EGate -> "e-Gates",
    Queues.FastTrack -> "Fast Track",
    Queues.Transfer -> "Tx")

  def queueDisplayName(name: String): QueueName = queueDisplayNames.getOrElse(name, name)

  def queueColour(queueName: String): String = queueName + "-user-desk-rec"

  def queueActualsColour(queueName: String): String = s"${queueColour(queueName)} actuals"

  case class Props(crunchState: CrunchState, airportConfig: AirportConfig, terminalName: TerminalName)

  case class State(showActuals: Boolean = false)

  implicit val propsReuse: Reusability[Props] = Reusability.by((props: Props) => {
    val lastUpdatedCm = props.crunchState.crunchMinutes.map(_.lastUpdated)
    val lastUpdatedFs = props.crunchState.flights.map(_.lastUpdated)
    (lastUpdatedCm, lastUpdatedFs)
  })

  implicit val stateReuse: Reusability[State] = Reusability.by((state: State) => {
    state.showActuals
  })

  val component = ScalaComponent.builder[Props]("Loader")
    .initialState[State](State(false))
    .renderPS((scope, props, state) => {
      def groupBy15 = CrunchApi.groupByX(15) _

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
        val headings = List(
          <.th(^.title := "Suggested deployment given available staff", s"Rec ${deskUnitLabel(queueName)}", ^.className := queueColumnClass),
          <.th(^.title := "Wait times with suggested deployments", "Est wait", ^.className := queueColumnClass))

        if (state.showActuals)
          headings ++ List(
            <.th(^.title := "Actual desks used", s"Act ${deskUnitLabel(queueName)}", ^.className := queueColumnActualsClass),
            <.th(^.title := "Actual wait times", "Act wait", ^.className := queueColumnActualsClass))
        else headings
      }

      def subHeadingLevel2(queueNames: Seq[QueueName]) = {
        val queueSubHeadings = queueNames.flatMap {
          case queueName => <.th(^.className := queueColour(queueName), "Pax") :: staffDeploymentSubheadings(queueName)
        }.toTagMod

        val list = List(queueSubHeadings,
          <.th(^.className := "total-deployed", "Rec", ^.title := "Total staff recommended for desks"),
          <.th(^.className := "total-deployed", "Dep", ^.title := "Total staff deployed based on assignments entered"))

        list
      }

      val showActsClassSuffix = if (state.showActuals) "-with-actuals" else ""
      val colsClass = s"cols-${queueNames.length}$showActsClassSuffix"

      def qth(queueName: String, xs: TagMod*) = <.th((^.className := queueName + "-user-desk-rec") :: xs.toList: _*)

      val queueHeadings: List[TagMod] = props.airportConfig.queues(props.terminalName).collect {
        case queueName if queueName != Queues.Transfer =>
          val colsToSpan = if (state.showActuals) 5 else 3
          qth(queueName, queueDisplayName(queueName), ^.colSpan := colsToSpan, ^.className := "top-heading")
      }.toList

      val headings: List[TagMod] = queueHeadings :+ <.th(^.className := "total-deployed", ^.colSpan := 2, "PCP")

      val terminalCrunchMinutes = groupBy15(
        CrunchApi.terminalCrunchMinutesByMinute(props.crunchState.crunchMinutes, props.terminalName),
        props.terminalName,
        Queues.queueOrder
      )

      val toggleShowActuals = (e: ReactEventFromInput) => {
        val newValue: Boolean = e.target.checked
        scope.modState(_.copy(showActuals = newValue))
      }

      <.div(
        if (props.airportConfig.hasActualDeskStats) {
          <.div(<.input.checkbox(^.checked := state.showActuals, ^.onChange ==> toggleShowActuals, ^.id := "show-actuals"),
            <.label(^.`for` := "show-actuals", "Show BlackJack Data"))
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
                val rowProps = TerminalDesksAndQueuesRow.Props(millis, minutes, props.airportConfig, props.terminalName, state.showActuals)
                TerminalDesksAndQueuesRow(rowProps)
            }.toTagMod))
      )
    })
    .componentDidMount((p) => Callback.log("TerminalDesksAndQueues did mount"))
    .build

  def apply(props: Props): VdomElement = component(props)
}
