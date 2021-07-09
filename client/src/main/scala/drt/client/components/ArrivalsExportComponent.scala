package drt.client.components

import drt.client.SPAMain
import drt.client.components.TerminalContentComponent.exportLink
import drt.client.components.styles.{DefaultFormFieldsStyle, WithScalaCssImplicits}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.{ExportArrivals, ViewMode}
import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SDateLike
import drt.shared.Terminals.Terminal
import drt.shared.dates.LocalDate
import io.kinoplan.scalajs.react.material.ui.core.{MuiFormLabel, MuiGrid, MuiTextField}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CallbackTo, CtorType, ReactEventFromInput, Reusability, ScalaComponent}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{ArrivalSource, ArrivalsAndSplitsView, DesksAndQueuesView}

object ArrivalsExportComponent extends WithScalaCssImplicits {
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
          "Arrivals",
          ^.className := "btn btn-default",
          VdomAttr("data-toggle") := "modal",
          VdomAttr("data-target") := "#multi-day-export",
          ^.onClick --> scope.modState(_.copy(showDialogue = true))
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
                <.h5(^.className := "modal-title", "Reflect Red List Pax Diversions?")
              ),
              <.div(
                ^.className := "modal-body",
                ^.id := "multi-day-export-modal-body",
                MuiGrid(container = true)(
                  MuiGrid(container = true, spacing = MuiGrid.Spacing.`16`)(
                    exportLink(
                      props.selectedDate,
                      props.terminal.toString,
                      ExportArrivals,
                      SPAMain.exportArrivalViewUrl(props.viewMode, props.terminal)
                    ),
                    if (props.loggedInUser.hasRole(ArrivalsAndSplitsView))
                      MuiGrid(item = true, xs = 3)(<.a(Icon.download, " Arrivals",
                        ^.className := "btn btn-default",
                        ^.href := SPAMain.absoluteUrl(s"export/arrivals/" +
                          s"${state.startDate.toISOString}/" +
                          s"${state.endDate.toISOString}/${props.terminal}"),
                        ^.target := "_blank",
                        ^.onClick --> {
                          Callback(GoogleEventTracker.sendEvent(props.terminal.toString, "click", "Export Arrivals", f"${state.startDate.toISOString} - ${state.endDate.toISOString}"))
                        }
                      ))
                    else
                      EmptyVdom,
                    if (props.loggedInUser.hasRole(DesksAndQueuesView))
                      List(
                        MuiGrid(item = true, xs = 3)(
                          <.a(Icon.download, s" Recommendations",
                            ^.className := "btn btn-default",
                            ^.key := "recs",
                            ^.href := SPAMain.absoluteUrl(s"export/desk-recs/${state.startDate.toISOString}/${state.endDate.toISOString}/${props.terminal}"),
                            ^.target := "_blank",
                            ^.onClick --> {
                              Callback(GoogleEventTracker.sendEvent(props.terminal.toString, "click", s"Export Desks", f"${state.startDate.toISOString} - ${state.endDate.toISOString}"))
                            }
                          ))
                        ,
                        MuiGrid(item = true, xs = 3)(
                          <.a(Icon.download, s" Deployments",
                            ^.key := "deps",
                            ^.className := "btn btn-default",
                            ^.href := SPAMain.absoluteUrl(s"export/desk-deps/${state.startDate.toISOString}/${state.endDate.toISOString}/${props.terminal}"),
                            ^.target := "_blank",
                            ^.onClick --> {
                              Callback(GoogleEventTracker.sendEvent(props.terminal.toString, "click", "Export Deployments", f"${state.startDate.toISOString} - ${state.endDate.toISOString}"))
                            }
                          )
                        )).toVdomArray
                    else
                      EmptyVdom,
                    if (props.loggedInUser.hasRole(ArrivalSource) && (state.endDate <= SDate.now().toLocalDate))
                      MuiGrid(item = true, xs = 3)(
                        <.a(Icon.file, " Live Feed",
                          ^.className := "btn btn-default",
                          ^.href := SPAMain.absoluteUrl(s"export/arrivals-feed/${props.terminal}/${state.startMillis}/${state.endMillis}/LiveFeedSource"),
                          ^.target := "_blank",
                          ^.onClick --> {
                            Callback(GoogleEventTracker.sendEvent(props.terminal.toString, "click", "Export Live Feed", f"${state.startDate.toISOString} - ${state.endDate.toISOString}"))
                          }
                        ))
                    else
                      EmptyVdom
                  )),

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
          )))
    })
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(terminal: Terminal, selectedDate: SDateLike, loggedInUser: LoggedInUser): VdomElement = component(Props(terminal, selectedDate, loggedInUser: LoggedInUser))
}
