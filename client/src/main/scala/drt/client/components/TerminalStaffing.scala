package drt.client.components

import java.io

import diode.data.{Empty, Pot, Ready}
import diode.react.ModelProxy
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react._
import org.scalajs.dom.html
import org.scalajs.dom.html.Div
import drt.client.logger._
import drt.client.services.JSDateConversions._
import drt.client.services._
import drt.shared.{MilliDate, SDateLike, StaffMovement, WorkloadsHelpers}
import drt.client.actions.Actions._
import drt.shared.FlightsApi.TerminalName
import drt.client.services.FixedPoints._

import scala.collection.immutable.{NumericRange, Seq}
import scala.scalajs.js.Date
import scala.util.{Success, Try}

object TerminalStaffing {

  case class Props(terminalName: TerminalName)

  class Backend($: BackendScope[Props, Unit]) {

    def render(props: Props) = {
      val staffingRCP = SPACircuit.connect(m => (m.shiftsRaw, m.fixedPointsRaw, m.staffMovements))
      staffingRCP((staffingMP: ModelProxy[(Pot[String], Pot[String], Seq[StaffMovement])]) => {
        <.div(
          staffingMP()._1.renderReady((rawShifts: String) => {
            staffingMP()._2.renderReady((rawFixedPoints: String) => {
              val movements = staffingMP() match {
                case (_, _, sm) => sm
              }

              val shifts: List[Try[StaffAssignment]] = StaffAssignmentParser(rawShifts).parsedAssignments.toList
              val fixedPoints: List[Try[StaffAssignment]] = StaffAssignmentParser(rawFixedPoints).parsedAssignments.toList
              <.div(
                <.div(^.className := "container",
                  <.div(^.className := "col-md-3", FixedPointsEditor(FixedPointsProps(rawFixedPoints, staffingMP, props.terminalName))),
                  <.div(^.className := "col-md-3", movementsEditor(movements, staffingMP, props.terminalName))
                ),
                <.div(^.className := "container",
                  <.div(^.className := "col-md-10", staffOverTheDay(movements, shifts, fixedPoints, props.terminalName)))
              )
            })
          })
        )
      })
    }
  }

  def filterByTerminal(fixedPoints: String, terminalName: String) = {
    fixedPoints.split("\n").filter(line => {
      val cells = line.split(",").map(cell => cell.trim())
      cells(1) == terminalName
    }).mkString("\n")
  }

  def staffOverTheDay(movements: Seq[StaffMovement], shifts: List[Try[StaffAssignment]], fixedPoints: List[Try[StaffAssignment]], terminalName: TerminalName): VdomTagOf[Div] = {
    val didParseFixedPointsFail = fixedPoints exists (s => s.isFailure)
    val didParseShiftsFail = shifts exists (s => s.isFailure)
    <.div(
      <.h2("Staff over the day"), if (didParseShiftsFail || didParseFixedPointsFail) {
        if (didParseShiftsFail)
          <.div(^.className := "error", "Error in Shifts")
        else ""
        if (fixedPoints exists (s => s.isFailure))
          <.div(^.className := "error", "Error in Fixed Points")
        else ""
      }
      else {
        val successfulShifts: List[StaffAssignment] = shifts.collect { case Success(s) => s }
        val successfulTerminalShifts = successfulShifts.filter(_.terminalName == terminalName)
        val successfulFixedPoints: List[StaffAssignment] = fixedPoints.collect { case Success(s) => s }
        val successfulTerminalFixedPoints = successfulFixedPoints.filter(_.terminalName == terminalName)
        val ss = StaffAssignmentService(successfulTerminalShifts)
        val fps = StaffAssignmentService(successfulTerminalFixedPoints)
        val staffWithShiftsAndMovementsAt = StaffMovements.staffAt(ss, fps)(movements.filter(_.terminalName == terminalName)) _
        staffingTableHourPerColumn(daysWorthOf15Minutes(SDate.today), staffWithShiftsAndMovementsAt)
      }
    )
  }

  def movementsEditor(movements: Seq[StaffMovement], mp: ModelProxy[(Pot[String], Pot[String], Seq[StaffMovement])], terminalName: TerminalName): VdomTagOf[Div] = {
    val terminalMovements = movements.filter(_.terminalName == terminalName)
    <.div(
      <.h2("Movements"),
      if (terminalMovements.nonEmpty)
        <.ul(^.className := "list-unstyled", terminalMovements.map(movement => {
          val remove = <.a(Icon.remove, ^.key := movement.uUID.toString, ^.onClick ==> ((e: ReactEventFromInput) => mp.dispatch(RemoveStaffMovement(0, movement.uUID))))
          <.li(remove, " ", MovementDisplay.toCsv(movement))
        }).toTagMod)
      else
        <.p("No movements recorded")
    )
  }

  case class FixedPointsProps(rawFixedPoints: String, mp: ModelProxy[(Pot[String], Pot[String], Seq[StaffMovement])], terminalName: TerminalName)

  case class FixedPointsState(rawFixedPoints: String)

  object FixedPointsEditor {
    val component = ScalaComponent.builder[FixedPointsProps]("FixedPointsEditor")
      .initialStateFromProps(props => {
        val onlyOurTerminal = filterTerminal(props.terminalName, props.rawFixedPoints)
        val withoutTerminalName = removeTerminalName(onlyOurTerminal)
        FixedPointsState(withoutTerminalName)
      })
      .renderPS((scope, props, state) => {
        val today: SDateLike = SDate.today
        val todayString = today.ddMMyyString

        val airportConfigRCP = SPACircuit.connect(model => model.airportConfig)

        val defaultExamples = Seq(
          "Roving Officer,any,{date},00:00,23:59,1"
        )

        <.div(
          <.h2("Fixed Points"),
          <.p("One entry per line with values separated by commas, e.g.:"),
          airportConfigRCP(airportConfigMP => {
            <.pre(
              airportConfigMP().renderReady(airportConfig => {
                val examples = if (airportConfig.fixedPointExamples.nonEmpty)
                  airportConfig.fixedPointExamples
                else
                  defaultExamples
                <.div(examples.map(line => <.div(line.replace("{date}", todayString))).toTagMod)
              })
            )
          }),
          <.textarea(^.value := state.rawFixedPoints, ^.className := "staffing-editor"),
          ^.onChange ==> ((e: ReactEventFromInput) => {
            val newRawFixedPoints = e.target.value
            scope.modState(_.copy(rawFixedPoints = newRawFixedPoints))
          }),
          <.button("Save", ^.onClick ==> ((e: ReactEventFromInput) => {
            val withTerminalName = addTerminalName(state.rawFixedPoints, props.terminalName)
            props.mp.dispatchCB(SaveFixedPoints(withTerminalName, props.terminalName))
          }))
        )
      }).build

    def apply(props: FixedPointsProps) = component(props)
  }

  def daysWorthOf15Minutes(startOfDay: SDateLike): NumericRange[Long] = {
    val timeMinPlusOneDay = startOfDay.addDays(1)
    val daysWorthOf15Minutes = startOfDay.millisSinceEpoch until timeMinPlusOneDay.millisSinceEpoch by (WorkloadsHelpers.oneMinute * 15)
    daysWorthOf15Minutes
  }

  def staffingTableHourPerColumn(daysWorthOf15Minutes: NumericRange[Long], staffWithShiftsAndMovements: (MilliDate) => Int) = {
    <.table(
      ^.className := "table table-striped table-xcondensed table-sm",
      <.tbody(
        daysWorthOf15Minutes.grouped(16).flatMap {
          hoursWorthOf15Minutes =>
            Seq(
              <.tr(^.key := s"hr-${hoursWorthOf15Minutes.headOption.getOrElse("empty")}", {
                hoursWorthOf15Minutes.map((t: Long) => {
                  val d = new Date(t)
                  val display = f"${d.getHours}%02d:${d.getMinutes}%02d"
                  <.th(^.key := t, display)
                }).toTagMod
              }),
              <.tr(^.key := s"vr-${hoursWorthOf15Minutes.headOption.getOrElse("empty")}",
                hoursWorthOf15Minutes.map(t => {
                  <.td(^.key := t, s"${staffWithShiftsAndMovements(t)}")
                }).toTagMod
              ))
        }.toTagMod
      )
    )
  }

  def apply(props: Props): VdomElement =
    component(props)

  private val component = ScalaComponent.builder[Props]("TerminalStaffing")
    .renderBackend[Backend]
    .build
}

object MovementDisplay {
  def toCsv(movement: StaffMovement) = {
    s"${movement.terminalName}, ${movement.reason}, ${displayDate(movement.time)}, ${displayTime(movement.time)}, ${movement.delta} staff"
  }

  def displayTime(time: MilliDate): String = {
    val startDate: SDateLike = SDate(time)
    f"${startDate.getHours}%02d:${startDate.getMinutes}%02d"
  }

  def displayDate(time: MilliDate): String = {
    val startDate: SDateLike = SDate(time)
    f"${startDate.getDate}%02d/${startDate.getMonth}%02d/${startDate.getFullYear - 2000}%02d"
  }
}
