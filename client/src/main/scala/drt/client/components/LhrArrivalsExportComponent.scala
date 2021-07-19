package drt.client.components

import drt.client.SPAMain
import drt.client.components.TerminalContentComponent.exportLink
import drt.client.components.styles.{DefaultFormFieldsStyle, WithScalaCssImplicits}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.{ExportArrivals, ExportArrivalsWithRedListDiversions, ExportArrivalsWithoutRedListDiversions, ViewMode}
import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{PortCode, SDateLike}
import drt.shared.Terminals.Terminal
import drt.shared.dates.LocalDate
import io.kinoplan.scalajs.react.material.ui.core.{MuiFormLabel, MuiGrid, MuiTextField}
import io.kinoplan.scalajs.react.material.ui.icons.{MuiIcons, MuiIconsModule}
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CallbackTo, CtorType, ReactEventFromInput, Reusability, ScalaComponent}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{ArrivalSource, ArrivalsAndSplitsView, DesksAndQueuesView}

object ArrivalsExportComponent {
  def apply(portCode: PortCode): (Terminal, SDateLike, LoggedInUser, ViewMode) => VdomElement = portCode match {
    case PortCode("LHR") => LhrArrivalsExportComponent.apply
    case PortCode("BHX") => BhxArrivalsExportComponent.apply
    case _ => (terminal, date, user, viewMode) => exportLink(
      date,
      terminal.toString,
      ExportArrivals,
      SPAMain.exportUrl(ExportArrivals, viewMode, terminal)
    )
  }
}

object LhrArrivalsExportComponent extends WithScalaCssImplicits {
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
                <.h5(^.className := "modal-title", "Reflect Red List Pax Diversions?")
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
                          ExportArrivalsWithRedListDiversions,
                          SPAMain.exportUrl(ExportArrivalsWithRedListDiversions, props.viewMode, props.terminal)
                        )
                      ),
                      MuiGrid(item = true, xs = 4)(
                        exportLink(
                          props.selectedDate,
                          props.terminal.toString,
                          ExportArrivalsWithoutRedListDiversions,
                          SPAMain.exportUrl(ExportArrivalsWithoutRedListDiversions, props.viewMode, props.terminal)
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
    component(Props(terminal, selectedDate, loggedInUser, viewMode))
}
