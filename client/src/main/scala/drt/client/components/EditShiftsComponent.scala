package drt.client.components

import diode.AnyAction.aType
import diode.data.Pot
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.client.services.handlers.{SaveShifts, UpdateShift}
import drt.shared.Shift
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^.{VdomTagOf, _}
import japgolly.scalajs.react.{BackendScope, CtorType, Reusability, ScalaComponent}
import org.scalajs.dom.html.Div
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

object EditShiftsComponent {

  case class State()

  case class Props(terminal: Terminal, portCode: String, shiftsPot: Pot[Seq[Shift]], shiftName: String, viewDate: Option[String], router: RouterCtl[Loc])

  implicit val propsReuse: Reusability[Props] = Reusability((a, b) => a == b)
  implicit val stateReuse: Reusability[State] = Reusability((a, b) => a == b)

  class Backend(scope: BackendScope[Props, State]) {

    import upickle.default.{macroRW, ReadWriter => RW}

    implicit val rw: RW[Shift] = macroRW

    private def getMonthOnStartDateCheck(viewDate: Option[String]): Int = {
      val today: SDateLike = SDate.now()
      viewDate match {
        case Some(date) =>
          val viewMonth = date.split("-")(1).toInt match {
            case month if month < 1 || month > 12 => today.getMonth
            case month => month
          }
          viewMonth
        case _ => today.getMonth
      }
    }

    private def startDateInLocalDate(month: Int): uk.gov.homeoffice.drt.time.LocalDate = {
      val today: SDateLike = SDate.now()
//      println(s"today month ${today.getMonth} $month")
      val year = if (today.getMonth > month + 1) today.getFullYear + 1 else today.getFullYear
      uk.gov.homeoffice.drt.time.LocalDate(year, month, 1)
    }

    def render(props: Props, state: State): VdomTagOf[Div] = {
      def confirmHandler(shifts: Seq[ShiftForm]): Unit = {
        val staffShifts = shifts.map { s =>
          val startDate: LocalDate = startDateInLocalDate(s.editStartMonth)
          Shift(
            port = props.portCode,
            terminal = props.terminal.toString,
            shiftName = s.name,
            startDate = startDate,
            startTime = s.startTime,
            endTime = s.endTime,
            endDate = None,
            staffNumber = s.defaultStaffNumber,
            frequency = None,
            createdBy = None,
            createdAt = System.currentTimeMillis()
          )
        }
        SPACircuit.dispatch(UpdateShift(staffShifts.headOption)) // Assuming only one shift is being edited
        props.router.set(TerminalPageTabLoc(props.terminal.toString, "Shifts", "60", Map.empty)).runNow()
      }

      <.div(
        props.shiftsPot.renderReady { shifts =>
          val shiftForms: Seq[ShiftForm] = shifts.zipWithIndex.map { case (s, index) =>
            ShiftForm(
              id = index + 1,
              name = s.shiftName,
              startTime = s.startTime,
              endTime = s.endTime,
              startDate = ShiftDate(s.startDate.year, s.startDate.month, s.startDate.day),
              defaultStaffNumber = s.staffNumber,
              startMonth = getMonthOnStartDateCheck(props.viewDate)
            )
          }.filter(s => s.name == props.shiftName) // Filter by the shift name passed in props
          println(s"Shifts: ${shiftForms.map(s => s"Id : ${s.id} Name: ${s.name}, StartTime: ${s.startTime}, EndTime: ${s.endTime}, StaffNumber: ${s.defaultStaffNumber}")}")

          AddShiftsFormComponent(ShiftFormProps(port = props.portCode, terminal = props.terminal.toString, interval = 30, initialShifts = shiftForms, confirmHandler = confirmHandler, isEdit = true))
        })
    }

  }

  private def stateFromProps(props: Props): State = {
    State()
  }

  val component: Component[Props, State, Backend, CtorType.Props] = ScalaComponent.builder[Props]("StaffingShiftsV2")
    .initialStateFromProps(stateFromProps)
    .renderBackend[Backend]
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(terminal: Terminal, portCode: String, shifts: Pot[Seq[Shift]], shiftName: String, viewDate: Option[String], router: RouterCtl[Loc]): Unmounted[Props, State, Backend] = component(Props(terminal, portCode, shifts, shiftName, viewDate, router))
}
