package drt.client.components

import diode.data.Pot
import drt.client.actions.Actions._
import drt.client.services.FixedPoints._
import drt.client.services.JSDateConversions._
import drt.client.services._
import drt.shared.FlightsApi.TerminalName
import drt.shared.{AirportConfig, MilliDate, SDateLike, StaffMovement}
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html.Div

import scala.collection.immutable.{NumericRange, Seq}
import scala.scalajs.js.Date
import scala.util.{Success, Try}

object DateRange {
  val start = SDate.midnightThisMorning()
  val end = start.addDays(1)
}

object TerminalStaffing {
  val oneMinute = 60000L

  case class Props(
                    terminalName: TerminalName,
                    potShifts: Pot[String],
                    potFixedPoints: Pot[String],
                    potStaffMovements: Pot[Seq[StaffMovement]],
                    airportConfig: AirportConfig
                  )

  def todaysMovements(movements: Seq[StaffMovement], start: MilliDate, end: MilliDate) = {
    movements.filter(m => m.time > start && m.time < end)
  }

  class Backend($: BackendScope[Props, Unit]) {

    def render(props: Props) = {

      <.div(
        props.potShifts.render((rawShifts: String) => {
          props.potFixedPoints.render((rawFixedPoints: String) => {
            props.potStaffMovements.render((movements: Seq[StaffMovement]) => {
              val shifts: List[Try[StaffAssignment]] = StaffAssignmentParser(rawShifts).parsedAssignments.toList
              val fixedPoints: List[Try[StaffAssignment]] = StaffAssignmentParser(rawFixedPoints).parsedAssignments.toList
              <.div(
                <.div(^.className := "container",
                  <.div(^.className := "col-md-3", FixedPointsEditor(FixedPointsProps(rawFixedPoints, props.airportConfig, props.terminalName))),
                  <.div(^.className := "col-md-3", movementsEditor(todaysMovements(movements, DateRange.start, DateRange.end), props.terminalName))
                ),
                <.div(^.className := "container",
                  <.div(^.className := "col-md-10", staffOverTheDay(movements, shifts, fixedPoints, props.terminalName)))
              )
            })
          })
        })
      )
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
          val ss = StaffAssignmentServiceWithDates(successfulTerminalShifts)
          val fps = StaffAssignmentServiceWithoutDates(successfulTerminalFixedPoints)
          val staffWithShiftsAndMovementsAt = StaffMovements.terminalStaffAt(ss, fps)(movements) _
          staffingTableHourPerColumn(terminalName, daysWorthOf15Minutes(SDate.midnightThisMorning), staffWithShiftsAndMovementsAt)
        }
      )
    }

    def movementsEditor(movements: Seq[StaffMovement], terminalName: TerminalName): VdomTagOf[Div] = {
      val terminalMovements = movements.filter(_.terminalName == terminalName)
      <.div(
        <.h2("Movements"),
        if (terminalMovements.nonEmpty)
          <.ul(^.className := "list-unstyled", terminalMovements.map(movement => {
            val remove = <.a(Icon.remove, ^.key := movement.uUID.toString, ^.onClick ==> ((e: ReactEventFromInput) => Callback(SPACircuit.dispatch(RemoveStaffMovement(0, movement.uUID)))))
            <.li(remove, " ", MovementDisplay.toCsv(movement))
          }).toTagMod)
        else
          <.p("No movements recorded")
      )
    }

    case class FixedPointsProps(rawFixedPoints: String, airportConfig: AirportConfig, terminalName: TerminalName)

    case class FixedPointsState(rawFixedPoints: String)

    object FixedPointsEditor {
      val component = ScalaComponent.builder[FixedPointsProps]("FixedPointsEditor")
        .initialStateFromProps(props => {
          val onlyOurTerminal = filterTerminal(props.terminalName, props.rawFixedPoints)
          val withoutTerminalName = removeTerminalNameAndDate(onlyOurTerminal)
          FixedPointsState(withoutTerminalName)
        })
        .renderPS((scope, props, state) => {

          val defaultExamples = Seq("Roving Officer, 00:00, 23:59, 1")
          val examples = if (props.airportConfig.fixedPointExamples.nonEmpty)
            props.airportConfig.fixedPointExamples
          else
            defaultExamples

          <.div(
            <.h2("Fixed Points"),
            <.p("One entry per line with values separated by commas, e.g.:"),

            <.pre(<.div(examples.map(line => <.div(line)).toTagMod)),
            <.textarea(^.value := state.rawFixedPoints, ^.className := "staffing-editor"),
            ^.onChange ==> ((e: ReactEventFromInput) => {
              val newRawFixedPoints = e.target.value
              scope.modState(_.copy(rawFixedPoints = newRawFixedPoints))
            }),
            <.button("Save", ^.onClick ==> ((e: ReactEventFromInput) => {
              val withTerminalName = addTerminalNameAndDate(state.rawFixedPoints, props.terminalName)
              Callback(SPACircuit.dispatch(SaveFixedPoints(withTerminalName, props.terminalName)))
            }))
          )
        }).build

      def apply(props: FixedPointsProps) = component(props)
    }

    def daysWorthOf15Minutes(startOfDay: SDateLike): NumericRange[Long] = {
      val timeMinPlusOneDay = startOfDay.addDays(1)
      val daysWorthOf15Minutes = startOfDay.millisSinceEpoch until timeMinPlusOneDay.millisSinceEpoch by (oneMinute * 15)
      daysWorthOf15Minutes
    }

    def staffingTableHourPerColumn(terminalName: TerminalName, daysWorthOf15Minutes: NumericRange[Long], staffWithShiftsAndMovements: (TerminalName, MilliDate) => Int) = {
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
                    <.td(^.key := t, s"${staffWithShiftsAndMovements(terminalName, t)}")
                  }).toTagMod
                ))
          }.toTagMod
        )
      )
    }
  }

  def apply(props: Props): VdomElement =
    component(props)

  private val component = ScalaComponent.builder[Props]("TerminalStaffing")
    .renderBackend[Backend]
    .build

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
}
