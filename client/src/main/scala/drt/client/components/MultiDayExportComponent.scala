package drt.client.components

import diode.UseValueEq
import drt.client.SPAMain
import drt.client.components.TerminalContentComponent.exportLink
import drt.client.components.styles.WithScalaCssImplicits
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.client.util.DateUtil.isNotValidDate
import drt.shared.CrunchApi.MillisSinceEpoch
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import io.kinoplan.scalajs.react.material.ui.core.{MuiButton, MuiGrid, MuiTextField}
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

  case class Props(portCode: PortCode, terminal: Terminal, terminals: Iterable[Terminal], viewMode: ViewMode, selectedDate: SDateLike, loggedInUser: LoggedInUser) extends UseValueEq

  case class StateDate(date: LocalDate, isNotValid: Boolean = false)

  case class State(startDate: StateDate, endDate: StateDate, showDialogue: Boolean = false) extends UseValueEq {

    def setStart(dateString: String, isNotValid: Boolean): State =
      copy(startDate = StateDate(LocalDate.parse(dateString).getOrElse(startDate.date), isNotValid))

    def setEnd(dateString: String, isNotValid: Boolean): State =
      copy(endDate = StateDate(LocalDate.parse(dateString).getOrElse(endDate.date), isNotValid))

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
    .renderPS { (scope, props, state) =>
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
        scope.modState(_.setStart(e.target.value, isNotValid = isNotValidDate(e.target.value)))
      }

      val setEndDate: ReactEventFromInput => CallbackTo[Unit] = e => {
        e.persist()
        scope.modState(_.setEnd(e.target.value, isNotValid = isNotValidDate(e.target.value)))
      }

      def showDialogue(event: Event): Callback = {
        event.preventDefault()
        scope.modState(_.copy(showDialogue = true))
      }

      if (props.loggedInUser.hasRole(BorderForceStaff))
        <.div(
          ^.className := "export-button-wrapper",
          MuiButton(color = Color.secondary, variant = "outlined", sx = SxProps(Map("fontWeight" -> "normal")))(
            MuiIcons(GetApp)(fontSize = "small"),
            "Multi Day Export",
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
                        val exports =
                          if (props.terminals.size > 1) List(ExportArrivalsSingleTerminal(props.terminal), ExportArrivalsCombinedTerminals)
                          else List(ExportArrivals(props.terminal))
                        exportLinksGroup(props, state, exports, "Arrivals")
                      } else EmptyVdom,
                      if (props.loggedInUser.hasRole(DesksAndQueuesView)) {
                        val exports = if (props.terminals.size > 1)
                          List(ExportDeskRecsSingleTerminal(props.terminal), ExportDeskRecsCombinedTerminals)
                        else List(ExportDeskRecs(props.terminal))
                        exportLinksGroup(props, state, exports, "Desks recommendations")
                      } else EmptyVdom,
                      if (props.loggedInUser.hasRole(DesksAndQueuesView)) {
                        val exports = if (props.terminals.size > 1)
                          List(ExportDeploymentsSingleTerminal(props.terminal), ExportDeploymentsCombinedTerminals)
                        else List(ExportDeployments(props.terminal))
                        exportLinksGroup(props, state, exports, "Available staff deployments")
                      } else EmptyVdom,
                      if (props.loggedInUser.hasRole(ArrivalSource) && (state.endDate.date <= SDate.now().toLocalDate))
                        exportLinksGroup(props, state, List(ExportLiveArrivalsFeed(props.terminal)), "Feeds")
                      else EmptyVdom
                    )
                  ),
                  <.div(
                    ^.className := "modal-footer",
                    ^.id := "multi-day-export-modal-footer",
                    <.button(
                      ^.key := "export-button",
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
    }
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
            SPAMain.exportDatesUrl(export, state.startDate.date, state.endDate.date),
            None,
            title
          )
        ).toVdomArray
      )
    )

  def apply(portCode: PortCode, terminal: Terminal, terminals: Iterable[Terminal], viewMode: ViewMode, selectedDate: SDateLike, loggedInUser: LoggedInUser): VdomElement =
    component(Props(portCode, terminal, terminals, viewMode, selectedDate, loggedInUser: LoggedInUser))
}
