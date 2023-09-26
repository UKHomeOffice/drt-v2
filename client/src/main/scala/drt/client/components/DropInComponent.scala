package drt.client.components

import diode.AnyAction.aType
import diode.UseValueEq
import diode.data.Pot
import diode.react.ReactConnectProxy
import drt.client.components.styles.{DrtTheme, WithScalaCssImplicits}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.client.services.handlers.{GetDropInRegistrations, CreateDropInRegistration}
import drt.shared.{DropIn, DropInRegistration}
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.core.system.{SxProps, ThemeProvider}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{BackendScope, Callback, CtorType, ReactEvent, Reusability, ScalaComponent}

import scala.language.postfixOps

case class DropInRegistrationModel(dropInRegistrations: Pot[Seq[DropInRegistration]]) extends UseValueEq

object DropInComponent extends WithScalaCssImplicits {

  case class State(dropIn: Option[DropIn], confirmRegister: Boolean, showDialog: Boolean)

  case class Props(dropIns: Seq[DropIn])

  class Backend($: BackendScope[Props, State]) {

    val modelRCP: ReactConnectProxy[DropInRegistrationModel] = SPACircuit.connect(model => DropInRegistrationModel(
      dropInRegistrations = model.dropInRegistrations
    ))

    private def roundToNearestHalfHour(hours: Double): Double = {
      val wholeHours = hours.toInt
      val fractionalHours = hours - wholeHours

      val roundedFraction = fractionalHours match {
        case x if x < 0.25 => 0.0
        case x if x < 0.75 => 0.5
        case _ => 1.0
      }

      wholeHours + roundedFraction
    }

    private def differenceInHours(startTime: Long, endTime: Long): String = {
      val differenceInMillis = Math.abs(startTime - endTime)
      val duration = roundToNearestHalfHour(differenceInMillis.toDouble / (1000 * 60 * 60))
      if (duration <= 1) f" $duration%.2f hour" else f" $duration%.2f hours"
    }

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
        modelRCP(modelMP => {
          val model: DropInRegistrationModel = modelMP()

          val showDropIns = {
            ThemeProvider(DrtTheme.theme)(
              <.div(
                MuiGrid(container = true, spacing = 2, sx = SxProps(Map(
                  "backgroundColor" -> "#FFFFFF",
                )))(
                  MuiGrid(sx = SxProps(Map(
                    "padding-top" -> "24px",
                    "color" -> DrtTheme.theme.palette.primary.`700`,
                    "font-size" -> DrtTheme.theme.typography.h3.fontSize,
                    "font-weight" -> DrtTheme.theme.typography.h3.fontWeight
                  )))(<.span(s"Book a Drop-in Session")),
                )),
              MuiGrid(sx = SxProps(Map(
                "backgroundColor" -> "#FFFFFF",
                "padding-top" -> "24px",
                "padding-left" -> "12px",
                "padding-right" -> "12px",
                "overflow" -> "hidden"
              )))(
                MuiGrid(container = true, spacing = 2)(
                  MuiGrid(item = true, xs = 12, sx = SxProps(Map(
                    "backgroundColor" -> "#FFFFFF",
                    "border" -> "8px solid #C0C7DE"
                  )))(
                    MuiPaper()(
                      MuiTable()(
                        MuiTableHead()(
                          MuiTableRow(sx =
                            SxProps(Map("backgroundColor" -> DrtTheme.theme.palette.primary.`50`)))(
                            MuiTableCell(sx = SxProps(Map("font-weight" -> "bold")))("Date"),
                            MuiTableCell(sx = SxProps(Map("font-weight" -> "bold")))("Time"),
                            MuiTableCell(sx = SxProps(Map("font-weight" -> "bold")))("Duration"),
                            MuiTableCell()(""),
                          )
                        ),
                        MuiTableBody()(
                          model.dropInRegistrations.renderReady(dropInRegistrations => {
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
                                  MuiTableCell()(SDate(tableItem.startTime).toISODateOnly),
                                  MuiTableCell()(SDate(tableItem.startTime).prettyTime),
                                  MuiTableCell()(differenceInHours(tableItem.startTime, tableItem.endTime)),
                                  MuiTableCell()(button),
                                )
                            }
                          }),
                        )
                      )
                    )
                  ))
              )
            )
          }
          <.div(
            showDropIns,
            DropInDialog(state.dropIn.map(dropIn => SDate(dropIn.startTime).toISODateOnly).getOrElse(""),
              state.dropIn.map(dropIn => SDate(dropIn.startTime).prettyTime).getOrElse(""),
              state.dropIn.map(dropIn => differenceInHours(dropIn.startTime, dropIn.endTime)).getOrElse(""),
              state.showDialog,
              handCloseDialog,
              handConfirmDialog,
              "Confirm your booking",
              "You are about to book a drop-in session for the date and time shown above. Please confirm this is correct.",
              "Confirm booking"),
            DropInDialog(state.dropIn.map(dropIn => SDate(dropIn.startTime).toISODateOnly).getOrElse(""),
              state.dropIn.map(dropIn => SDate(dropIn.startTime).prettyTime).getOrElse(""),
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
