package drt.client.components

import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{ArrivalSource, ArrivalsAndSplitsView, DesksAndQueuesView}
import drt.client.SPAMain
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SDateLike
import drt.shared.Terminals.Terminal
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ScalaComponent}

object MultiDayExportComponent {
  val today: SDateLike = SDate.now()
  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(terminal: Terminal,
                   selectedDate: SDateLike,
                   loggedInUser: LoggedInUser)

  case class State(startDay: Int,
                   startMonth: Int,
                   startYear: Int,
                   endDay: Int,
                   endMonth: Int,
                   endYear: Int,
                   showDialogue: Boolean = false
                  ) {
    def startMillis: MillisSinceEpoch = SDate(startYear, startMonth, startDay).millisSinceEpoch

    def endMillis: MillisSinceEpoch = SDate(endYear, endMonth, endDay).millisSinceEpoch
  }

  implicit val stateReuse: Reusability[State] = Reusability.derive[State]
  implicit val terminalReuse: Reusability[Terminal] = Reusability.derive[Terminal]
  implicit val propsReuse: Reusability[Props] = Reusability.by(p => (p.terminal, p.selectedDate.millisSinceEpoch))

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("SnapshotSelector")
    .initialStateFromProps(p => State(
      startDay = p.selectedDate.getDate(),
      startMonth = p.selectedDate.getMonth(),
      startYear = p.selectedDate.getFullYear(),
      endDay = p.selectedDate.getDate(),
      endMonth = p.selectedDate.getMonth(),
      endYear = p.selectedDate.getFullYear()
    ))
    .renderPS((scope, props, state) => {

      val showClass = if (state.showDialogue) "show" else "fade"

      <.div(
        <.a(
          "Multi Day Export",
          ^.className := "btn btn-default",
          VdomAttr("data-toggle") := "modal",
          VdomAttr("data-target") := "#multi-day-export",
          ^.onClick --> scope.modState(_.copy(showDialogue = true))
        ),
        <.div(^.className := "multi-day-export modal " + showClass, ^.id := "#multi-day-export", ^.tabIndex := -1, ^.role := "dialog",
          <.div(
            ^.className := "modal-dialog modal-dialog-centered",
            ^.role := "document",
            <.div(
              ^.className := "modal-content",
              <.div(
                ^.className := "modal-header",
                <.h5(^.className := "modal-title", "Choose dates to export")
              ),
              <.div(
                ^.className := "modal-body",
                DateSelector("From", today, d => {
                  scope.modState(_.copy(startDay = d.getDate(), startMonth = d.getMonth(), startYear = d.getFullYear()))
                }),
                DateSelector("To", today, d => {
                  scope.modState(_.copy(endDay = d.getDate(), endMonth = d.getMonth(), endYear = d.getFullYear()))
                }),
                if (state.startMillis > state.endMillis)
                  <.div(^.className := "multi-day-export__error", "Please select an end date that is after the start date.")
                else
                  EmptyVdom,

                <.div(
                  <.div(^.className := "multi-day-export-links",

                    if (props.loggedInUser.hasRole(ArrivalsAndSplitsView))
                      <.a("Export Arrivals",
                        ^.className := "btn btn-default",
                        ^.href := SPAMain.absoluteUrl(s"export/arrivals/" +
                          s"${SDate(state.startMillis).toLocalDate.toISOString}/" +
                          s"${SDate(state.endMillis).toLocalDate.toISOString}/${props.terminal}"),
                        ^.target := "_blank",
                        ^.onClick --> {
                          Callback(GoogleEventTracker.sendEvent(props.terminal.toString, "click", "Export Arrivals", f"${state.startYear}-${state.startMonth}%02d-${state.startDay}%02d - ${state.endYear}-${state.endMonth}%02d-${state.endDay}%02d"))
                        }
                      ) else EmptyVdom,
                    if (props.loggedInUser.hasRole(DesksAndQueuesView))
                      List(<.a("Export Recs",
                        ^.className := "btn btn-default",
                        ^.href := SPAMain.absoluteUrl(s"export/desk-recs/${SDate(state.startMillis).toLocalDate.toISOString}/${SDate(state.endMillis).toLocalDate.toISOString}/${props.terminal}"),
                        ^.target := "_blank",
                        ^.onClick --> {
                          Callback(GoogleEventTracker.sendEvent(props.terminal.toString, "click", "Export Desks", f"${state.startYear}-${state.startMonth}%02d-${state.startDay}%02d - ${state.endYear}-${state.endMonth}%02d-${state.endDay}%02d"))
                        }
                      ),
                        <.a("Export Deployments",
                          ^.className := "btn btn-default",
                          ^.href := SPAMain.absoluteUrl(s"export/desk-deps/${SDate(state.startMillis).toLocalDate.toISOString}/${SDate(state.endMillis).toLocalDate.toISOString}/${props.terminal}"),
                          ^.target := "_blank",
                          ^.onClick --> {
                            Callback(GoogleEventTracker.sendEvent(props.terminal.toString, "click", "Export Desks", f"${state.startYear}-${state.startMonth}%02d-${state.startDay}%02d - ${state.endYear}-${state.endMonth}%02d-${state.endDay}%02d"))
                          }
                        )
                      ).toVdomArray
                    else EmptyVdom,
                    if (props.loggedInUser.hasRole(ArrivalSource) && (state.endMillis <= SDate.now().millisSinceEpoch))
                      <.a("Export Live Feed",
                        ^.className := "btn btn-default",
                        ^.href := SPAMain.absoluteUrl(s"export/arrivals-feed/${props.terminal}/${state.startMillis}/${state.endMillis}/LiveFeedSource"),
                        ^.target := "_blank",
                        ^.onClick --> {
                          Callback(GoogleEventTracker.sendEvent(props.terminal.toString, "click", "Export Arrivals", f"${state.startYear}-${state.startMonth}%02d-${state.startDay}%02d - ${state.endYear}-${state.endMonth}%02d-${state.endDay}%02d"))
                        }
                      ) else EmptyVdom

                  )
                )
              ),
              <.div(
                ^.className := "modal-footer",
                <.button(
                  ^.className := "btn btn-link",
                  VdomAttr("data-dismiss") := "modal", "Close",
                  ^.onClick --> scope.modState(_.copy(showDialogue = false))
                )
              )
            )
          )
        ))
    })
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(terminal: Terminal, selectedDate: SDateLike, loggedInUser: LoggedInUser): VdomElement = component(Props(terminal, selectedDate, loggedInUser: LoggedInUser))
}
