package drt.client.components

import diode.AnyAction.aType
import diode.data.Pot
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.client.services.handlers.UpdateShift
import japgolly.scalajs.react.callback.Callback
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^.{VdomTagOf, _}
import japgolly.scalajs.react.{BackendScope, CtorType, Reusability, ScalaComponent}
import org.scalajs.dom.html.Div
import uk.gov.homeoffice.drt.Shift
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

object EditShiftsComponent {


  case class Props(terminal: Terminal,
                   portCode: String,
                   shiftsPot: Pot[Seq[Shift]],
                   shiftName: String,
                   shiftDate: Option[String],
                   viewDate: Option[String],
                   router: RouterCtl[Loc])

  implicit val propsReuse: Reusability[Props] = Reusability((a, b) => a == b)

  class Backend(scope: BackendScope[Props, Unit]) {

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

    private def shiftDateToShiftDate(shiftDate: Option[String]): LocalDate = {
      val parts = shiftDate.map(_.split("-")).getOrElse(Array())
      if (parts.length == 3) {
        val year = parts(0).toInt
        val month = parts(1).toInt
        val day = parts(2).toInt
        uk.gov.homeoffice.drt.time.LocalDate(year = year, month = month, day = day)
      } else {
        val today: SDateLike = SDate.now()
        uk.gov.homeoffice.drt.time.LocalDate(today.getFullYear, today.getMonth, today.getDate)
      }
    }

    private def startDateInLocalDate(startDate: ShiftDate): uk.gov.homeoffice.drt.time.LocalDate = {
      uk.gov.homeoffice.drt.time.LocalDate(year = startDate.year, month = startDate.month, day = startDate.day)
    }

    def render(props: Props): VdomTagOf[Div] = {
      def confirmHandler(shifts: Seq[ShiftForm]): Unit = {
        val staffShifts = shifts.map { s =>
          val startDate: LocalDate = startDateInLocalDate(s.startDate)
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
        SPACircuit.dispatch(UpdateShift(staffShifts.headOption, props.shiftName))
        Callback(GoogleEventTracker.sendEvent(props.terminal.toString, action = "Shifts", label = "update")).runNow()
        props.router.set(TerminalPageTabLoc(props.terminal.toString, "Shifts", "60", Map.empty)).runNow()
      }

      <.div(
        props.shiftsPot.renderReady { shifts =>
          val shiftForms: Seq[ShiftForm] = shifts
            .filter(s => s.shiftName == props.shiftName && s.startDate == shiftDateToShiftDate(props.shiftDate))
            .zipWithIndex.map { case (s, index) =>
              ShiftForm(
                id = index + 1,
                name = s.shiftName,
                startTime = s.startTime,
                endTime = s.endTime,
                startDate = ShiftDate(year = s.startDate.year, month = s.startDate.month, day = s.startDate.day),
                defaultStaffNumber = s.staffNumber,
              )
            }

          AddShiftsFormComponent(
            ShiftFormProps(port = props.portCode,
              terminal = props.terminal.toString,
              interval = 30,
              initialShifts = shiftForms,
              confirmHandler = confirmHandler,
              isEdit = true))
        })
    }

  }


  val component: Component[Props, Unit, Backend, CtorType.Props] = ScalaComponent.builder[Props]("StaffingEditShiftsV2")
    .renderBackend[Backend]
    .configure(Reusability.shouldComponentUpdate)
    .build


  def apply(terminal: Terminal,
            portCode: String,
            shifts: Pot[Seq[Shift]],
            shiftName: String,
            shiftDate: Option[String],
            viewDate: Option[String],
            router: RouterCtl[Loc]): Unmounted[Props, Unit, Backend] = component(Props(terminal, portCode, shifts, shiftName, shiftDate, viewDate, router))
}
