package drt.client.components

import drt.client.SPAMain
import drt.client.components.TerminalContentComponent.exportLink
import drt.client.components.styles.WithScalaCssImplicits
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{ExportArrivalsCombinedTerminals, ExportArrivalsSingleTerminal, ExportArrivalsWithRedListDiversions, ExportArrivalsWithoutRedListDiversions, ViewMode}
import drt.shared.SDateLike
import drt.shared.Terminals.Terminal
import drt.shared.dates.LocalDate
import io.kinoplan.scalajs.react.material.ui.core.MuiGrid
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, Reusability, ScalaComponent}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.ArrivalsAndSplitsView

object BhxArrivalsExportComponent extends WithScalaCssImplicits {
  val today: SDateLike = SDate.now()
  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(terminal: Terminal, selectedDate: SDateLike, loggedInUser: LoggedInUser, viewMode: ViewMode)

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
              <.div(
                ^.className := "modal-header",
                <.h5(^.className := "modal-title", "Single terminal or both")
              ),
              <.div(
                ^.className := "modal-body",
                ^.id := "arrivals-export-modal-body",
                if (props.loggedInUser.hasRole(ArrivalsAndSplitsView))
                  MuiGrid(container = true)(
                    MuiGrid(container = true, spacing = MuiGrid.Spacing.`16`)(
                      MuiGrid(item = true, xs = 4)(
                        exportLink(
                          props.selectedDate,
                          props.terminal.toString,
                          ExportArrivalsSingleTerminal,
                          SPAMain.exportUrl(ExportArrivalsSingleTerminal, props.viewMode, props.terminal)
                        )
                      ),
                      MuiGrid(item = true, xs = 4)(
                        exportLink(
                          props.selectedDate,
                          props.terminal.toString,
                          ExportArrivalsCombinedTerminals,
                          SPAMain.exportUrl(ExportArrivalsCombinedTerminals, props.viewMode, props.terminal)
                        )
                      )
                    ))
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

  def apply: (Terminal, SDateLike, LoggedInUser, ViewMode) => VdomElement = (terminal: Terminal, selectedDate: SDateLike, loggedInUser: LoggedInUser, viewMode: ViewMode) =>
    component(Props(terminal, selectedDate, loggedInUser, viewMode))}
