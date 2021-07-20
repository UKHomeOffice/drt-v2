package drt.client.components

import drt.client.SPAMain
import drt.client.components.TerminalContentComponent.exportLink
import drt.client.components.styles.WithScalaCssImplicits
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{ExportArrivals, ExportArrivalsCombinedTerminals, ExportArrivalsSingleTerminal, ExportArrivalsWithRedListDiversions, ExportArrivalsWithoutRedListDiversions, ExportType, ViewMode}
import drt.shared.Terminals.Terminal
import drt.shared.dates.LocalDate
import drt.shared.{PortCode, SDateLike}
import io.kinoplan.scalajs.react.material.ui.core.MuiGrid
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, Reusability, ScalaComponent}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.ArrivalsAndSplitsView


object ArrivalsExportComponent extends WithScalaCssImplicits {
  val today: SDateLike = SDate.now()
  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(terminal: Terminal,
                   selectedDate: SDateLike,
                   loggedInUser: LoggedInUser,
                   viewMode: ViewMode,
                   title: String,
                   exports: List[ExportType],
                  )

  case class State(showDialogue: Boolean = false)

  implicit val localDateReuse: Reusability[LocalDate] = Reusability.derive[LocalDate]
  implicit val stateReuse: Reusability[State] = Reusability.derive[State]
  implicit val terminalReuse: Reusability[Terminal] = Reusability.derive[Terminal]
  implicit val propsReuse: Reusability[Props] = Reusability.by(p => (p.terminal, p.selectedDate.millisSinceEpoch))

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("MultiDayExportComponent")
    .initialStateFromProps(p => State(false))
    .renderPS((scope, props, state) => {
      val showClass = if (state.showDialogue) "show" else "fade"
      <.div(
        <.a(
          Icon.download,
          " Arrivals",
          ^.className := "btn btn-default",
          VdomAttr("data-toggle") := "modal",
          VdomAttr("data-target") := "#arrivals-export",
          ^.onClick --> scope.modState(_.copy(showDialogue = true))
        ),
        <.div(^.className := "arrivals-export modal " + showClass, ^.id := "#arrivals-export", ^.tabIndex := -1, ^.role := "dialog",
          <.div(
            ^.className := "modal-dialog modal-dialog-centered",
            ^.id := "arrivals-export-modal-dialog",
            ^.role := "document",
            <.div(
              ^.className := "modal-content",
              <.div(^.className := "modal-header", <.h5(^.className := "modal-title", props.title)),
              <.div(
                ^.className := "modal-body",
                ^.id := "arrivals-export-modal-body",
                if (props.loggedInUser.hasRole(ArrivalsAndSplitsView))
                  exportLinks(props.exports, props.selectedDate, props.terminal, props.viewMode)
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
          )))
    })
    .configure(Reusability.shouldComponentUpdate)
    .build

  private def exportLinks(exports: List[ExportType], date: SDateLike, terminal: Terminal, viewMode: ViewMode): html_<^.VdomElement =
    MuiGrid(container = true)(
      MuiGrid(container = true, spacing = MuiGrid.Spacing.`16`)(
        exports.map(export =>
          MuiGrid(item = true, xs = 4)(
            exportLink(date, terminal.toString, export, SPAMain.exportUrl(export, viewMode, terminal))
          )).toVdomArray
      ))

  def componentFactory(title: String, exports: List[ExportType]): (Terminal, SDateLike, LoggedInUser, ViewMode) => VdomElement =
    (terminal: Terminal, selectedDate: SDateLike, loggedInUser: LoggedInUser, viewMode: ViewMode) =>
      component(Props(terminal, selectedDate, loggedInUser, viewMode, title, exports))

  def apply(portCode: PortCode): (Terminal, SDateLike, LoggedInUser, ViewMode) => VdomElement = portCode match {
    case PortCode("LHR") => componentFactory(
      "Reflect Red List Pax Diversions?",
      List(ExportArrivalsWithRedListDiversions, ExportArrivalsWithoutRedListDiversions)
    )
    case PortCode("BHX") => componentFactory(
      "Single terminal or both?",
      List(ExportArrivalsSingleTerminal, ExportArrivalsCombinedTerminals))
    case _ => (terminal, date, user, viewMode) =>
      exportLink(
        date,
        terminal.toString,
        ExportArrivals,
        SPAMain.exportUrl(ExportArrivals, viewMode, terminal)
      )
  }
}
