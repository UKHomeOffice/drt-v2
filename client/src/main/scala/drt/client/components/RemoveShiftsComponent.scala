package drt.client.components

import diode.AnyAction.aType
import diode.data.Pot
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.client.services.handlers.{RemoveShift, UpdateShift}
import japgolly.scalajs.react.callback.Callback
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^.{VdomTagOf, _}
import japgolly.scalajs.react.{CtorType, Reusability, ScalaComponent}
import org.scalajs.dom.html.Div
import uk.gov.homeoffice.drt.Shift
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

object RemoveShiftsComponent {


  case class Props(terminal: Terminal,
                   portCode: String,
                   shiftsPot: Pot[Seq[Shift]],
                   shiftName: String,
                   shiftDate: Option[String],
                   shiftStartTime: Option[String],
                   viewDate: Option[String],
                   router: RouterCtl[Loc])

  implicit val propsReuse: Reusability[Props] = Reusability((a, b) => a == b)

  class Backend {

    import upickle.default.{macroRW, ReadWriter => RW}

    implicit val rw: RW[Shift] = macroRW

    private def dateStringToLocalDate(shiftDate: Option[String]): LocalDate = {
      shiftDate.flatMap { dateStr =>
        val parts = dateStr.split("-")
        if (parts.length == 3) {
          Some(uk.gov.homeoffice.drt.time.LocalDate(parts(0).toInt, parts(1).toInt, parts(2).toInt))
        } else None
      }.getOrElse {
        val today: SDateLike = SDate.now()
        uk.gov.homeoffice.drt.time.LocalDate(today.getFullYear, today.getMonth, today.getDate)
      }
    }

    private def startDateInLocalDate(startDate: ShiftDate): uk.gov.homeoffice.drt.time.LocalDate = {
      uk.gov.homeoffice.drt.time.LocalDate(year = startDate.year, month = startDate.month, day = startDate.day)
    }

    def render(props: Props): VdomTagOf[Div] = {

      def cancelHandler(): Unit = {
        props.router.set(TerminalPageTabLoc(props.terminal.toString, "Shifts", "60")).runNow()
      }

      def confirmHandler(shift: ShiftForm): Unit = {

        val startDate: LocalDate = startDateInLocalDate(shift.startDate)
        val staffShift = Shift(
          port = props.portCode,
          terminal = props.terminal.toString,
          shiftName = shift.name,
          startDate = startDate,
          startTime = shift.startTime,
          endTime = shift.endTime,
          endDate = None,
          staffNumber = shift.defaultStaffNumber,
          frequency = None,
          createdBy = None,
          createdAt = System.currentTimeMillis()
        )

        SPACircuit.dispatch(RemoveShift(Some(staffShift), props.shiftName))
        Callback(GoogleEventTracker.sendEvent(props.terminal.toString, action = "Shifts", label = "removed")).runNow()
        props.router.set(TerminalPageTabLoc(props.terminal.toString, "Shifts", "60", Map("shifts" -> "removed"))).runNow()
      }

      <.div(
        props.shiftsPot.renderReady { shifts =>
          val shiftForms: Seq[ShiftForm] = shifts
            .filter(s => s.shiftName == props.shiftName && s.startDate == dateStringToLocalDate(props.shiftDate) && s.startTime == props.shiftStartTime.getOrElse(s.startTime))
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

          shiftForms.headOption match {
            case Some(shiftForm) =>
              ConfirmRemoveShiftComponent(
                RemoveShiftFormProps(
                  shift = shiftForm,
                  removeShiftConfirmHandler = confirmHandler,
                  cancelRemoveShiftHandler = cancelHandler
                ))
            case None =>
              <.div("No matching shift found to remove.")
          }

        })
    }
  }

  val component: Component[Props, Unit, Backend, CtorType.Props] = ScalaComponent.builder[Props]("StaffingRemoveShiftsV2")
    .renderBackend[Backend]
    .configure(Reusability.shouldComponentUpdate)
    .build


  def apply(terminal: Terminal,
            portCode: String,
            shifts: Pot[Seq[Shift]],
            shiftName: String,
            shiftDate: Option[String],
            shiftStartTime: Option[String],
            viewDate: Option[String],
            router: RouterCtl[Loc]): Unmounted[Props, Unit, Backend] = component(Props(terminal, portCode, shifts, shiftName, shiftDate, shiftStartTime, viewDate, router))
}
