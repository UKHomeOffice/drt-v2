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

object TerminalExportComponent extends WithScalaCssImplicits {
  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(terminal: Terminal,
                   selectedDate: SDateLike,
                   loggedInUser: LoggedInUser,
                   viewMode: ViewMode,
                   terminals: Iterable[Terminal],
                   exportName: String
                  ) extends UseValueEq

  case class State(showDialogue: Boolean)


  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("TerminalExportComponent")
    .initialState(State(false))
    .renderPS((scope, props, state) => {
      val showClass = if (state.showDialogue) "show" else "fade"
      val exportNameLC = props.exportName.toLowerCase

      def showDialogue(event: Event): Callback = {
        event.preventDefault()
        scope.modState(_.copy(showDialogue = true))
      }

      val title = if (props.terminals.size > 1) s"Export $exportNameLC for ${props.terminal} or all terminals" else s"${props.exportName} for ${props.terminal.toString}"
      val exports = exportNameLC match {
        case "deployments" => if (props.terminals.size > 1)
          List(ExportDeploymentsSingleTerminal(props.terminal), ExportDeploymentsCombinedTerminals)
        else List(ExportDeployments(props.terminal))
        case "arrivals" => if (props.terminals.size > 1)
          List(ExportArrivalsSingleTerminal(props.terminal), ExportArrivalsCombinedTerminals)
        else List(ExportArrivals(props.terminal))
        case "recommendations" => if (props.terminals.size > 1)
          List(ExportDeskRecsSingleTerminal(props.terminal), ExportDeskRecsCombinedTerminals)
        else List(ExportDeskRecs(props.terminal))
        case _ => List()
      }

      <.div(
        ^.className := "export-button-wrapper",
        MuiButton(color = Color.primary, variant = "outlined", size = "medium")(
          MuiIcons(GetApp)(fontSize = "small"),
          s"Export ${props.exportName} (.csv)",
          ^.className := "btn btn-default",
          ^.href := "#",
          VdomAttr("data-toggle") := "modal",
          VdomAttr("data-target") := "#terminals-export",
          ^.onClick ==> showDialogue,
        ),
        <.div(^.className := s"terminals-export modal " + showClass, ^.id := s"#terminals-export", ^.tabIndex := -1, ^.role := "dialog",
          <.div(
            ^.className := "modal-dialog modal-dialog-centered",
            ^.id := "terminals-export-modal-dialog",
            ^.role := "document",
            <.div(
              ^.className := "modal-content",
              <.div(^.className := "modal-header", <.h5(^.className := "modal-title", title)),
              <.div(
                ^.className := "modal-body",
                ^.id := "terminals-export-modal-body",
                if (props.loggedInUser.hasRole(ArrivalsAndSplitsView))
                  exportLinks(exportNameLC, exports, props.selectedDate, props.terminal, props.viewMode)
                else
                  EmptyVdom,
                <.div(
                  ^.className := "modal-footer",
                  ^.id := "terminals-export-modal-footer",
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

  private def exportLinks(exportName: String, exports: List[ExportType], date: SDateLike, terminal: Terminal, viewMode: ViewMode): html_<^.VdomElement =
    MuiGrid(container = true)(
      MuiGrid(container = true, spacing = 2)(
        exports.map(export =>
          MuiGrid(item = true, xs = 4)(
            exportLink(date, terminal.toString, export, SPAMain.exportUrl(export, viewMode), None, exportName)
          )).toVdomArray
      ))

  private val defaultDeploymentExport: (Terminal, SDateLike, LoggedInUser, ViewMode, String) => VdomElement = (term, date, _, viewMode, exportName) => {
    exportName.toLowerCase match {
      case "deployments" =>
        exportLink(
          exportDay = date,
          terminalName = term.toString,
          exportType = ExportDeployments(term),
          exportUrl = SPAMain.exportUrl(ExportDeployments(term), viewMode),
          title = exportName.toLowerCase
        ).apply()
      case "recommendations" =>
        exportLink(
          exportDay = date,
          terminalName = term.toString,
          exportType = ExportDeskRecs(term),
          exportUrl = SPAMain.exportUrl(ExportDeskRecs(term), viewMode),
          title = exportName.toLowerCase
        ).apply()
      case "arrivals" =>
        exportLink(
          exportDay = date,
          terminalName = term.toString,
          exportType = ExportArrivals(term),
          exportUrl = SPAMain.exportUrl(ExportArrivals(term), viewMode),
          title = exportName.toLowerCase
        ).apply()
      case _ => <.div()
    }


  }


  def componentFactory(terminals: Iterable[Terminal], exportName: String): (Terminal, SDateLike, LoggedInUser, ViewMode) => VdomElement =
    (terminal: Terminal, selectedDate: SDateLike, loggedInUser: LoggedInUser, viewMode: ViewMode) =>
      if (terminals.size > 1)
        component(Props(terminal, selectedDate, loggedInUser, viewMode, terminals, exportName))
      else
        defaultDeploymentExport(terminal, selectedDate, loggedInUser, viewMode, exportName)
}
