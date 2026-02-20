package drt.client.components

import diode.AnyAction.aType
import diode.UseValueEq
import diode.data.Pot
import diode.react.ReactConnectProxy
import drt.client.components.styles.{DrtReactTheme, WithScalaCssImplicits}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.client.services.handlers.{CreateDropInRegistration, GetDropInRegistrations}
import drt.shared.{DropIn, DropInRegistration}
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.core.system.{SxProps, ThemeProvider}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{BackendScope, Callback, CtorType, ReactEvent, ScalaComponent}

import scala.language.postfixOps

trait DropInTimeDisplay {
  def formatDuration(minutes: Int): String = {
    val roundedMinutes = (math.round(minutes.toFloat / 15) * 15).toInt
    val displayMinutes = if (roundedMinutes % 60 < 1) "" else s"${roundedMinutes % 60} minutes"

    val hours = {
      roundedMinutes / 60
    }
    val displayHours = if (hours != 1) s"$hours hours" else s"$hours hour"

    if (roundedMinutes < 60) s"$roundedMinutes minutes"
    else s"$displayHours $displayMinutes".trim
  }

  def differenceInHours(startTime: Long, endTime: Long): String = {
    val differenceInMillis = Math.abs(startTime - endTime)
    formatDuration(differenceInMillis.toInt / (1000 * 60))
  }

}

case class DropInRegistrationModel(dropInRegistrations: Pot[Seq[DropInRegistration]]) extends UseValueEq

object DropInComponent extends WithScalaCssImplicits with DropInTimeDisplay {

  case class State(dropIn: Option[DropIn], confirmRegister: Boolean, showDialog: Boolean)

  case class Props(dropIns: Seq[DropIn])

  class Backend($: BackendScope[Props, State]) {

    val modelRCP: ReactConnectProxy[DropInRegistrationModel] = SPACircuit.connect(model => DropInRegistrationModel(
      dropInRegistrations = model.dropInRegistrations
    ))


    def componentDidMount() = Callback {
      SPACircuit.dispatch(GetDropInRegistrations())
    }

    def handleConfirmedClose(e: ReactEvent) = {
      Callback(SPACircuit.dispatch(GetDropInRegistrations())) >>
        $.modState(s => s.copy(confirmRegister = false))
    }

    def openDialog(dropIn: DropIn)(e: ReactEvent) = {
      $.modState(s => s.copy(
        dropIn = Option(dropIn),
        showDialog = true
      ))
    }

    def handCloseDialog(e: ReactEvent) = {
      $.modState(s => s.copy(showDialog = false))
    }

    def handConfirmDialog(e: ReactEvent) = {
      Callback($.state.runNow().dropIn.map(dropIn => SPACircuit.dispatch(CreateDropInRegistration(dropIn.id.map(_.toString).getOrElse(""))))) >>
        Callback(SPACircuit.dispatch(GetDropInRegistrations())) >>
        $.modState(s => s.copy(showDialog = false)) >>
        $.modState(s => s.copy(confirmRegister = true))
    }

    def render(props: Props, state: State) = {
      <.div(
        MuiTypography(variant = "h2")("Book a Drop-in Session"),
        modelRCP(modelMP => {
          val model: DropInRegistrationModel = modelMP()

          val showDropIns = {
            props.dropIns.nonEmpty match {
              case true =>
                ThemeProvider(DrtReactTheme)(
                  MuiGrid(sx = SxProps(Map(
                    "backgroundColor" -> "#FFFFFF",
                    "paddingTop" -> "24px",
                    "paddingLeft" -> "12px",
                    "paddingRight" -> "12px",
                    "overflow" -> "hidden"
                  )))(MuiTypography(sx = SxProps(Map("marginBottom" -> "16px")))(
                    "To book a drop-in session, please click the 'Book' button on the row that is most convenient for you."),
                    MuiGrid(container = true, spacing = 2, sx = SxProps(Map("width" -> "60%")))(
                      MuiGrid(item = true, xs = 12, sx = SxProps(Map(
                        "backgroundColor" -> "#FFFFFF",
                      )))(
                        model.dropInRegistrations.renderReady(dropInRegistrations => {
                          MuiTable()(
                            MuiTableHead()(
                              MuiTableRow()(
                                MuiTableCell(sx = SxProps(Map("fontWeight" -> "bold")))("Date"),
                                MuiTableCell(sx = SxProps(Map("fontWeight" -> "bold")))("Time"),
                                MuiTableCell(sx = SxProps(Map("fontWeight" -> "bold")))("Duration"),
                                MuiTableCell()(""),
                              )
                            ),
                            MuiTableBody()(
                              props.dropIns.zipWithIndex.toVdomArray {
                                case (tableItem, _) =>
                                  val button: VdomNode =
                                    dropInRegistrations.exists(_.dropInId == tableItem.id.getOrElse(0)) match {
                                      case true =>
                                        MuiButton(variant = "outlined", disableRipple = true, color = "success")("Booked")
                                      case false =>
                                        MuiButton(variant = "outlined", color = "primary")("Book", ^.onClick ==> openDialog(tableItem))
                                    }
                                  MuiTableRow()(
                                    MuiTableCell()(SDate(tableItem.startTime).`DD-Month-YYYY`),
                                    MuiTableCell()(SDate(tableItem.startTime).prettyTimeWithMeridian),
                                    MuiTableCell()(differenceInHours(tableItem.startTime, tableItem.endTime)),
                                    MuiTableCell()(button),
                                  )
                              })
                          )
                        })
                      ))
                  )
                )
              case false =>
                MuiTypography(variant = "h6", sx = SxProps(
                  Map("paddingTop" -> "24px",
                    "paddingBottom" -> "24px",
                    "display" -> "flex",
                    "justifyContent" -> "center",
                    "alignItems" -> "center")
                ))("No drop-in sessions available. Please check back later.")
            }
          }
          <.div(
            showDropIns,
            DropInDialog(state.dropIn.map(dropIn => SDate(dropIn.startTime).`DD-Month-YYYY`).getOrElse(""),
              state.dropIn.map(dropIn => SDate(dropIn.startTime).prettyTimeWithMeridian).getOrElse(""),
              state.dropIn.map(dropIn => differenceInHours(dropIn.startTime, dropIn.endTime)).getOrElse(""),
              state.showDialog,
              handCloseDialog,
              handConfirmDialog,
              "Confirm your booking",
              "You are about to book a drop-in session for the date and time shown above. Please confirm this is correct.",
              "Confirm booking"),
            DropInDialog(state.dropIn.map(dropIn => SDate(dropIn.startTime).`DD-Month-YYYY`).getOrElse(""),
              state.dropIn.map(dropIn => SDate(dropIn.startTime).prettyTimeWithMeridian).getOrElse(""),
              state.dropIn.map(dropIn => differenceInHours(dropIn.startTime, dropIn.endTime)).getOrElse(""),
              state.confirmRegister,
              handleConfirmedClose,
              handleConfirmedClose,
              "Booking Confirmed",
              "Your booking is confirmed please check your email for invite to teams meeting link.",
              "Continue"),
          )
        }))
    }

  }

  val component: Component[Props, State, Backend, CtorType.Props] =
    ScalaComponent
      .builder[Props]("DropInComponent")
      .initialState(State(None, false, false))
      .renderBackend[Backend]
      .componentDidMount(scope => scope.backend.componentDidMount())
      .build

  def apply(dropIns: Seq[DropIn]): VdomElement =
    component(Props(dropIns))

}
