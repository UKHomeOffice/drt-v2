package spatutorial.client.components

import diode.data.{Empty, Pot, Ready}
import diode.react.ModelProxy
import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react._
import org.scalajs.dom.html
import org.scalajs.dom.html.Div
import spatutorial.client.logger._
import spatutorial.client.services.JSDateConversions._
import spatutorial.client.services._
import spatutorial.shared.{MilliDate, SDate, StaffMovement, WorkloadsHelpers}

import scala.collection.immutable.{NumericRange, Seq}
import scala.scalajs.js.Date
import scala.util.{Success, Try}

object Staffing {

  case class Props()

  class Backend($: BackendScope[Props, Unit]) {

    def render(props: Props) = {
      val shiftsAndMovementsRCP = SPACircuit.connect(m => (m.shiftsRaw, m.staffMovements))
      shiftsAndMovementsRCP((shiftsAndMovementsMP: ModelProxy[(Pot[String], Seq[StaffMovement])]) => {
        val rawShifts = shiftsAndMovementsMP() match {
          case (Ready(shifts), _) => shifts
          case _ => ""
        }
        val movements = shiftsAndMovementsMP() match {
          case (_, sm) => sm
        }

        val shifts: List[Try[Shift]] = ShiftParser(rawShifts).parsedShifts.toList
        val didParseFail = shifts exists (s => s.isFailure)
        <.div(
          <.div(^.className := "container",
            <.h1("Staffing"),
            <.div(^.className := "col-md-3", shiftsEditor(rawShifts, shiftsAndMovementsMP)),
            <.div(^.className := "col-md-2"),
            <.div(^.className := "col-md-3", movementsEditor(movements, shiftsAndMovementsMP))
          ),
          <.div(^.className := "container",
            <.div(^.className := "col-md-10", staffOverTheDay(movements, shifts, didParseFail)))
        )
      })
    }
  }

  def staffOverTheDay(movements: Seq[StaffMovement], shifts: List[Try[Shift]], didParseFail: Boolean): ReactTagOf[Div] = {
    <.div(
      <.h2("Staff over the day"), if (didParseFail) {
        <.div(^.className := "error", "Error in shifts")
      }
      else {
        val successfulShifts: List[Shift] = shifts.collect { case Success(s) => s }
        val ss = ShiftService(successfulShifts)
        val staffWithShiftsAndMovementsAt = StaffMovements.staffAt(ss)(movements) _
        staffingTableHourPerColumn(daysWorthOf15Minutes(SDate.today), staffWithShiftsAndMovementsAt)
      }
    )
  }

  def movementsEditor(movements: Seq[StaffMovement], mp: ModelProxy[(Pot[String], Seq[StaffMovement])]): ReactTagOf[Div] = {
    <.div(
      <.h2("Movements"),
      if (movements.length > 0)
        <.ul(^.className := "list-unstyled", movements.map(movement => {
          val remove = <.a(Icon.remove, ^.key := movement.uUID.toString, ^.onClick ==> ((e: ReactEventI) => mp.dispatch(RemoveStaffMovement(0, movement.uUID))))
          <.li(remove, " ", MovementDisplay.toCsv(movement))
        }))
      else
        <.p("No movements recorded")
    )
  }

  def shiftsEditor(rawShifts: String, mp: ModelProxy[(Pot[String], Seq[StaffMovement])]): ReactTagOf[html.Div] = {

    val today: SDate = SDate.today
    val todayString = today.ddMMyyString

    val shiftExamples = Seq(
      s"Midnight shift,${todayString},00:00,00:59,14",
      s"Night shift,${todayString},01:00,06:59,6",
      s"Morning shift,${todayString},07:00,13:59,25",
      s"Afternoon shift,${todayString},14:00,16:59,13",
      s"Evening shift,${todayString},17:00,23:59,20"
    )

    <.div(
      <.h2("Shifts"),
      <.p("One shift per line with values separated by commas, e.g.:"),
      <.pre(shiftExamples.map(<.div(_))),
      <.textarea(^.value := rawShifts,
        ^.className := "staffing-editor",
        ^.onChange ==> ((e: ReactEventI) => mp.dispatch(SetShifts(e.target.value)))),
      <.button("Save", ^.onClick ==> ((e: ReactEventI) => mp.dispatch(SaveShifts(rawShifts))))
    )
  }

  def daysWorthOf15Minutes(startOfDay: SDate): NumericRange[Long] = {
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
              <.tr(^.key := s"hr-${hoursWorthOf15Minutes.head}", {
                hoursWorthOf15Minutes.map((t: Long) => {
                  val d = new Date(t)
                  val display = f"${d.getHours}%02d:${d.getMinutes}%02d"
                  <.th(^.key := t, display)
                })
              }),
              <.tr(^.key := s"vr-${hoursWorthOf15Minutes.head}",
                hoursWorthOf15Minutes.map(t => {
                  log.info(s"$t => ${staffWithShiftsAndMovements(t)}")
                  <.td(^.key := t, s"${staffWithShiftsAndMovements(t)}")
                })
              ))
        }
      )
    )
  }

  private def simpleStaffingTable(daysWorthOf15Minutes: NumericRange[Long], ss: ShiftService) = {
    <.table(
      <.tr({
        daysWorthOf15Minutes.map((t: Long) => {
          val d = new Date(t)
          val display = f"${d.getHours}%02d:${d.getMinutes}"
          <.td(^.key := t, display)
        })
      }),
      <.tr(
        daysWorthOf15Minutes.map(t => <.td(^.key := t, s"${StaffMovements.staffAt(ss)(Nil)(t)}"))
      )
    )
  }

  def apply(): ReactElement =
    component(Props())

  private val component = ReactComponentB[Props]("Staffing")
    .renderBackend[Backend]
    .build
}


object MovementDisplay  {
  def toCsv(movement: StaffMovement) = {
    s"${movement.reason},${displayDate(movement.time)},${displayTime(movement.time)},${movement.delta}"
  }

  def displayTime(time: MilliDate): String = {
    val startDate: SDate = SDate(time)
    f"${startDate.getHours}%02d:${startDate.getMinutes}%02d"
  }

  def displayDate(time: MilliDate): String = {
    val startDate: SDate = SDate(time)
    f"${startDate.getDate}%02d/${startDate.getMonth}%02d/${startDate.getFullYear - 2000}%02d"
  }
}
