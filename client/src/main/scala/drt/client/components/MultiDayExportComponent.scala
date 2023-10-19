package drt.client.components

import diode.UseValueEq
import drt.client.SPAMain
import drt.client.components.TerminalContentComponent.exportLink
import drt.client.components.styles.{DefaultFormFieldsStyle, WithScalaCssImplicits}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.client.util.DateUtil.isNotValidDate
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.redlist.LhrRedListDatesImpl
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import io.kinoplan.scalajs.react.material.ui.core.{MuiButton, MuiFormLabel, MuiGrid, MuiTextField}
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.GetApp
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.all.onClick.Event
import japgolly.scalajs.react.vdom.html_<^
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CallbackTo, CtorType, ReactEventFromInput, ScalaComponent}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{ArrivalSource, ArrivalsAndSplitsView, BorderForceStaff, DesksAndQueuesView}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.scalajs.js

object MultiDayExportComponent extends WithScalaCssImplicits {
  val today: SDateLike = SDate.now()
  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(portCode: PortCode, terminal: Terminal, viewMode: ViewMode, selectedDate: SDateLike, loggedInUser: LoggedInUser) extends UseValueEq

  case class StateDate(date: LocalDate, isNotValid: Boolean = false)

  case class State(startDate: StateDate, endDate: StateDate, showDialogue: Boolean = false) extends UseValueEq {

    def setStart(dateString: String, isNotValid: Boolean): State = if (isNotValid) copy(startDate = StateDate(startDate.date, isNotValid = true)) else copy(startDate = StateDate(LocalDate.parse(dateString).getOrElse(startDate.date)))

    def setEnd(dateString: String, isNotValid: Boolean): State = if (isNotValid) copy(endDate = StateDate(endDate.date, isNotValid = true)) else copy(endDate = StateDate(LocalDate.parse(dateString).getOrElse(endDate.date)))

    def startMillis: MillisSinceEpoch = SDate(startDate.date).millisSinceEpoch

    def endMillis: MillisSinceEpoch = SDate(endDate.date).millisSinceEpoch
  }

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("MultiDayExportComponent")
    .initialStateFromProps { p =>
      State(
        startDate = StateDate(p.selectedDate.toLocalDate),
        endDate = StateDate(p.selectedDate.toLocalDate)
      )
    }
    .renderPS((scope, props, state) => {

      val showClass = if (state.showDialogue) "show" else "fade"

      def datePickerWithLabel(setDate: ReactEventFromInput => CallbackTo[Unit], label: String, currentDate: LocalDate): html_<^.VdomElement = {
        val key = label.replace("[^0-9a-zA-Z]+", "-")
        MuiGrid(container = true, spacing = 2)(
          MuiGrid(item = true, xs = 4)(
            ^.key := s"date-picker-date-$key",
            MuiTextField(label = label.toVdom)(
              ^.`type` := "date",
              ^.defaultValue := SDate(currentDate).toISODateOnly,
              ^.onChange ==> setDate
            )
          ),
        )
      }

      val setStartDate: ReactEventFromInput => CallbackTo[Unit] = e => {
        e.persist()
        isNotValidDate(e.target.value) match {
          case true =>
            scope.modState(_.setStart(e.target.value, true))

          case _ =>
            scope.modState(_.setStart(e.target.value, false))
        }
      }

      val setEndDate: ReactEventFromInput => CallbackTo[Unit] = e => {
        e.persist()
        isNotValidDate(e.target.value) match {
          case true =>
            scope.modState(_.setEnd(e.target.value, true))
          case _ =>
            scope.modState(_.setEnd(e.target.value, false))
        }
      }

      def showDialogue(event: Event): Callback = {
        event.preventDefault()
        scope.modState(_.copy(showDialogue = true))
      }

      if (props.loggedInUser.hasRole(BorderForceStaff))
        <.div(
          ^.className := "export-button-wrapper",
          MuiButton(color = Color.primary, variant = "outlined", size = "medium")(
            MuiIcons(GetApp)(fontSize = "small"),
            "Multi Day Export",
            ^.className := "btn btn-default",
            VdomAttr("data-toggle") := "modal",
            VdomAttr("data-target") := "#multi-day-export",
            ^.href := "#",
            ^.onClick ==> showDialogue,
          ),
          <.div(^.className := "multi-day-export modal " + showClass, ^.id := "#multi-day-export", ^.tabIndex := -1, ^.role := "dialog",
            <.div(
              ^.className := "modal-dialog modal-dialog-centered",
              ^.id := "multi-day-export-modal-dialog",
              ^.role := "document",
              <.div(
                ^.className := "modal-content",
                <.div(
                  ^.className := "modal-header",
                  <.h5(^.className := "modal-title", "Choose dates to export")
                ),
                <.div(
                  ^.className := "modal-body",
                  ^.id := "multi-day-export-modal-body",
                  MuiGrid(container = true)(
                    <.div(^.style := js.Dictionary("display" -> "flex", "flexDirection" -> "column", "gap" -> 8),
                      <.div(^.style := js.Dictionary("display" -> "flex", "gap" -> 8),
                        datePickerWithLabel(setStartDate, "From", state.startDate.date),
                        datePickerWithLabel(setEndDate, "To", state.endDate.date),
                      ),
                      if (state.startDate.date > state.endDate.date)
                        <.div(^.className := "multi-day-export__error", "Please select an end date that is after the start date.")
                      else
                        EmptyVdom,

                      if (props.loggedInUser.hasRole(ArrivalsAndSplitsView)) {
                        val exports = props.portCode match {
                          case PortCode("LHR") if LhrRedListDatesImpl.dayHasPaxDiversions(SDate(state.endDate.date)) =>
                            List(ExportArrivalsWithRedListDiversions("Reflect pax diversions"), ExportArrivalsWithoutRedListDiversions("Don't reflect pax diversions"))
                          case PortCode("BHX") => List(ExportArrivalsSingleTerminal, ExportArrivalsCombinedTerminals)
                          case _ => List(ExportArrivals)
                        }
                        exportLinksGroup(props, state, exports, "Arrivals")
                      } else EmptyVdom,
                      if (props.loggedInUser.hasRole(DesksAndQueuesView))
                        exportLinksGroup(props, state, List(ExportDeskRecs, ExportDeployments), "Desks and queues")
                      else EmptyVdom,
                      if (props.loggedInUser.hasRole(ArrivalSource) && (state.endDate.date <= SDate.now().toLocalDate))
                        exportLinksGroup(props, state, List(ExportLiveArrivalsFeed), "Feeds")
                      else EmptyVdom
                    )
                  ),
                  <.div(
                    ^.className := "modal-footer",
                    ^.id := "multi-day-export-modal-footer",
                    <.button(
                      ^.className := "btn btn-link",
                      VdomAttr("data-dismiss") := "modal", "Close",
                      ^.onClick --> scope.modState(_.copy(showDialogue = false))
                    )
                  )
                )
              )
            ))
        )
      else EmptyVdom
    })
    .build


  private def exportLinksGroup(props: Props, state: State, exports: List[ExportType], title: String): VdomElement =
    <.div(^.style := js.Dictionary("display" -> "flex", "flexDirection" -> "column", "gap" -> "8px"),
      title,
      <.div(^.style := js.Dictionary("display" -> "flex", "gap" -> 8),
        exports.map(export =>
          exportLink(
            props.selectedDate,
            props.terminal.toString,
            export,
            SPAMain.exportDatesUrl(export, state.startDate.date, state.endDate.date, props.terminal)
          )
        ).toVdomArray
      )
    )

  def apply(portCode: PortCode, terminal: Terminal, viewMode: ViewMode, selectedDate: SDateLike, loggedInUser: LoggedInUser): VdomElement = component(Props(portCode, terminal, viewMode, selectedDate, loggedInUser: LoggedInUser))
}
