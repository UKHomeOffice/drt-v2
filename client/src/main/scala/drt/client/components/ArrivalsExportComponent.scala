package drt.client.components

import diode.UseValueEq
import drt.client.SPAMain
import drt.client.components.TerminalContentComponent.exportLink
import drt.client.components.styles.WithScalaCssImplicits
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services._
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.core.{MuiButton, MuiGrid}
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.GetApp
import io.kinoplan.scalajs.react.material.ui.icons._
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.all.onClick.Event
import japgolly.scalajs.react.vdom.html_<^
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ScalaComponent}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.ArrivalsAndSplitsView
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDateLike

object ArrivalsExportComponent extends WithScalaCssImplicits {
  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(terminal: Terminal,
                   selectedDate: SDateLike,
                   loggedInUser: LoggedInUser,
                   viewMode: ViewMode,
                   terminals: Iterable[Terminal],
                  ) extends UseValueEq

  case class State(showDialogue: Boolean = false)


  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("MultiDayExportComponent")
    .initialState(State(false))
    .renderPS((scope, props, state) => {
      val showClass = if (state.showDialogue) "show" else "fade"

      def showDialogue(event: Event): Callback = {
        event.preventDefault()
        scope.modState(_.copy(showDialogue = true))
      }

      val title = if (props.terminals.size > 1) s"Export arrivals for ${props.terminal} or all terminals" else s"Arrivals for ${props.terminal.toString}"
      val exports = if (props.terminals.size > 1) List(ExportArrivalsSingleTerminal(props.terminal), ExportArrivalsCombinedTerminals) else List(ExportArrivals(props.terminal))

      <.div(
        ^.className := "export-button-wrapper",
        MuiButton(color = Color.primary, variant = "outlined", size = "medium")(
          MuiIcons(GetApp)(fontSize = "small"),
          "Arrivals",
          ^.className := "btn btn-default",
          ^.href := "#",
          VdomAttr("data-toggle") := "modal",
          VdomAttr("data-target") := "#arrivals-export",
          ^.onClick ==> showDialogue,
        ),
        <.div(^.className := "arrivals-export modal " + showClass, ^.id := "#arrivals-export", ^.tabIndex := -1, ^.role := "dialog",
          <.div(
            ^.className := "modal-dialog modal-dialog-centered",
            ^.id := "arrivals-export-modal-dialog",
            ^.role := "document",
            <.div(
              ^.className := "modal-content",
              <.div(^.className := "modal-header", <.h5(^.className := "modal-title", title)),
              <.div(
                ^.className := "modal-body",
                ^.id := "arrivals-export-modal-body",
                if (props.loggedInUser.hasRole(ArrivalsAndSplitsView))
                  exportLinks(exports, props.selectedDate, props.terminal, props.viewMode)
                else
                  EmptyVdom,
                <.div(
                  ^.className := "modal-footer",
                  ^.id := "arrivals-export-modal-footer",
                  <.button(
                    ^.className := "btn btn-link",
                    VdomAttr("data-dismiss") := "modal", "Close",
                    ^.onClick --> scope.modState(_.copy(showDialogue = false))
                  )
                )
              )
            )
          )
        )
      )
    })
    .build

  private def exportLinks(exports: List[ExportType], date: SDateLike, terminal: Terminal, viewMode: ViewMode): html_<^.VdomElement =
    MuiGrid(container = true)(
      MuiGrid(container = true, spacing = 2)(
        exports.map(export =>
          MuiGrid(item = true, xs = 4)(
            exportLink(date, terminal.toString, export, SPAMain.exportUrl(export, viewMode), None, "arrivals")
          )).toVdomArray
      ))

  def componentFactory(terminals: Iterable[Terminal]): (Terminal, SDateLike, LoggedInUser, ViewMode) => VdomElement =
    (terminal: Terminal, selectedDate: SDateLike, loggedInUser: LoggedInUser, viewMode: ViewMode) =>
      component(Props(terminal, selectedDate, loggedInUser, viewMode, terminals))
}
