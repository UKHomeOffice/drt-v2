package drt.client.components

import drt.client.TableViewUtils.queueDisplayName
import drt.client.services.JSDateConversions
import drt.shared.Crunch.{CrunchMinute, CrunchState, MillisSinceEpoch}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.{AirportConfig, MilliDate, Queues}
import japgolly.scalajs.react.vdom.html_<^.{<, VdomElement, _}
import japgolly.scalajs.react.{ReactEventFromInput, ScalaComponent}

object TerminalDesksAndQueues {

  case class Props(crunchState: CrunchState, airportConfig: AirportConfig, terminalName: TerminalName)
  case class State(showActuals: Boolean = false)

  val component = ScalaComponent.builder[Props]("Loader")
    .initialState[State](State(false))
    .renderPS((scope, props, state) => {

      def queueColour(queueName: String): String = queueName + "-user-desk-rec"
      def queueActualsColour(queueName: String): String = s"${queueColour(queueName)} actuals"

      def renderRow(mCm: (MillisSinceEpoch, List[CrunchMinute])) = {
        val crunchMinutesByQueue = mCm._2.map(qm => Tuple2(qm.queueName, qm)).toMap
        val minute = mCm._1

        val queueTds = crunchMinutesByQueue.flatMap {
          case (qn, cm) =>
            val queueCells = List(
              <.td(^.className := queueColour(qn), s"${Math.round(cm.paxLoad)}"),
              <.td(^.className := queueColour(qn),^.title := s"Rec: ${cm.deskRec}", s"${cm.deployedDesks.map(Math.round(_)).getOrElse("-")}"),
              <.td(^.className := queueColour(qn),^.title := s"With Rec: ${cm.waitTime}", s"${cm.deployedWait.map(Math.round(_)).getOrElse("-")}")
            )
            if (state.showActuals) {
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
        val downMovementPopup = StaffDeploymentsAdjustmentPopover(props.airportConfig.terminalNames, Option(props.terminalName), "-", "Staff decrease...", SDate(minute), SDate(minute).addHours(1), "left", "-")()
        val upMovementPopup = StaffDeploymentsAdjustmentPopover(props.airportConfig.terminalNames, Option(props.terminalName), "+", "Staff increase...", SDate(minute), SDate(minute).addHours(1), "left", "+")()

        val pcpTds = List(<.td(^.className := s"total-deployed $ragClass", totalRequired),
          <.td(^.className := s"total-deployed $ragClass staff-adjustments", <.span(downMovementPopup, <.span(^.className := "deployed", totalDeployed), upMovementPopup)))
        <.tr((<.td(SDate(MilliDate(minute)).toHoursAndMinutes()) :: queueTds.toList ++ pcpTds).toTagMod)
      }

      def groupBy15(crunchMinutes: Seq[(MillisSinceEpoch, Set[CrunchMinute])]) = {
        val groupSize = 15
        crunchMinutes.grouped(groupSize).toList.map(group => {
          val byQueueName = group.flatMap(_._2).groupBy(_.queueName)
          val startMinute = group.map(_._1).min
          val queueCrunchMinutes = Queues.queueOrder.collect {
            case qn if byQueueName.contains(qn) =>
              CrunchMinute(
                props.terminalName,
                qn,
                startMinute,
                byQueueName(qn).map(_.paxLoad).sum,
                byQueueName(qn).map(_.workLoad).sum,
                byQueueName(qn).map(_.deskRec).max,
                byQueueName(qn).map(_.waitTime).max,
                Option(byQueueName(qn).map(_.deployedDesks.getOrElse(0)).max),
                Option(byQueueName(qn).map(_.deployedWait.getOrElse(0)).max),
                Option(byQueueName(qn).map(_.actDesks.getOrElse(0)).max),
                Option(byQueueName(qn).map(_.actWait.getOrElse(0)).max)
              )
          }
          (startMinute, queueCrunchMinutes)
        })
      }

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

      val defaultNumberOfQueues = 3
      val showActsClassSuffix = if (state.showActuals) "-with-actuals" else ""
      val colsClass = s"cols-${queueNames.length}$showActsClassSuffix"

      def qth(queueName: String, xs: TagMod*) = <.th((^.className := queueName + "-user-desk-rec") :: xs.toList: _*)

      val queueHeadings: List[TagMod] = props.airportConfig.queues(props.terminalName).collect {
        case queueName if queueName != Queues.Transfer =>
          val colsToSpan = if (state.showActuals) 5 else 3
          qth(queueName, queueDisplayName(queueName), ^.colSpan := colsToSpan, ^.className := "top-heading")
      }.toList

      val headings: List[TagMod] = queueHeadings :+ <.th(^.className := "total-deployed", ^.colSpan := 2, "PCP")

      val terminalCrunchMinutes = groupBy15(props.crunchState.crunchMinutes
        .filter(_.terminalName == props.terminalName)
        .groupBy(_.minute)
        .toSeq
        .sortBy(_._1))

      val toggleShowActuals = (e: ReactEventFromInput) => {
        val newValue: Boolean = e.target.checked
        scope.modState(_.copy(showActuals = newValue))
      }

      <.div(
        if (props.airportConfig.hasActualDeskStats) {
          <.div(<.input.checkbox(^.checked := state.showActuals, ^.onChange ==> toggleShowActuals, ^.id := "show-actuals"),
            <.label(^.`for` := "show-actuals", "Show actual desks & wait times"))
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
            terminalCrunchMinutes.map(renderRow).toTagMod))
      )
    })
    .build

  def apply(props: Props): VdomElement = component(props)
}
