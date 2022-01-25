package drt.client.components

import diode.UseValueEq
import drt.client.SPAMain
import drt.client.components.TerminalContentComponent.exportLink
import drt.client.components.styles.{DefaultFormFieldsStyle, WithScalaCssImplicits}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.redlist.LhrRedListDatesImpl
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.core.{MuiButton, MuiFormLabel, MuiGrid, MuiTextField}
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.GetApp
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CallbackTo, CtorType, ReactEventFromInput, ScalaComponent}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{ArrivalSource, ArrivalsAndSplitsView, DesksAndQueuesView}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

object MultiDayExportComponent extends WithScalaCssImplicits {
  val today: SDateLike = SDate.now()
  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  case class Props(portCode: PortCode, terminal: Terminal, viewMode: ViewMode, selectedDate: SDateLike, loggedInUser: LoggedInUser) extends UseValueEq

  case class State(startDate: LocalDate, endDate: LocalDate, showDialogue: Boolean = false) extends UseValueEq {

    def setStart(dateString: String): State = copy(startDate = LocalDate.parse(dateString).getOrElse(startDate))

    def setEnd(dateString: String): State = copy(endDate = LocalDate.parse(dateString).getOrElse(endDate))

    def startMillis: MillisSinceEpoch = SDate(startDate).millisSinceEpoch

    def endMillis: MillisSinceEpoch = SDate(endDate).millisSinceEpoch
  }

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("MultiDayExportComponent")
    .initialStateFromProps { p =>
      State(
        startDate = p.selectedDate.toLocalDate,
        endDate = p.selectedDate.toLocalDate
      )
    }
    .renderPS((scope, props, state) => {

      val showClass = if (state.showDialogue) "show" else "fade"

      val setStartDate: ReactEventFromInput => CallbackTo[Unit] = e => {
        e.persist()
        scope.modState(_.setStart(e.target.value))
      }

      val setEndDate: ReactEventFromInput => CallbackTo[Unit] = e => {
        e.persist()
        scope.modState(_.setEnd(e.target.value))
      }

      val gridXs = 4
      <.div(
        MuiButton(color = Color.default, variant = "outlined", size = "medium")(
          MuiIcons(GetApp)(fontSize = "small"),
          "Multi Day Export",
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
                <.h5(^.className := "modal-title", "Choose dates to export")
              ),
              <.div(
                ^.className := "modal-body",
                ^.id := "multi-day-export-modal-body",
                MuiGrid(container = true)(
                  MuiGrid(container = true, spacing = MuiGrid.Spacing.`16`)(
                    datePickerWithLabel(setStartDate, "From", state.startDate),
                    datePickerWithLabel(setEndDate, "To", state.endDate),
                    if (state.startDate > state.endDate)
                      MuiGrid(item = true, xs = 12)(<.div(^.className := "multi-day-export__error", "Please select an end date that is after the start date."))
                    else
                      EmptyVdom,

                    if (props.loggedInUser.hasRole(ArrivalsAndSplitsView)) {
                      val exports = props.portCode match {
                        case PortCode("LHR") if LhrRedListDatesImpl.dayHasPaxDiversions(SDate(state.endDate)) =>
                          List(ExportArrivalsWithRedListDiversions("Reflect pax diversions"), ExportArrivalsWithoutRedListDiversions("Don't reflect pax diversions"))
                        case PortCode("BHX") => List(ExportArrivalsSingleTerminal, ExportArrivalsCombinedTerminals)
                        case _ => List(ExportArrivals)
                      }
                      exportLinksGroup(props, state, gridXs, exports, "Arrivals")
                    } else EmptyVdom,
                    if (props.loggedInUser.hasRole(DesksAndQueuesView))
                      exportLinksGroup(props, state, gridXs, List(ExportDeskRecs, ExportDeployments), "Desks and queues")
                    else EmptyVdom,
                    if (props.loggedInUser.hasRole(ArrivalSource) && (state.endDate <= SDate.now().toLocalDate))
                      exportLinksGroup(props, state, gridXs, List(ExportLiveArrivalsFeed), "Feeds")
                    else EmptyVdom
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
    .build

  private def datePickerWithLabel(setDate: ReactEventFromInput => CallbackTo[Unit], label: String, currentDate: LocalDate): html_<^.VdomElement = {
    MuiGrid(container = true, spacing = MuiGrid.Spacing.`16`)(
      MuiGrid(item = true, xs = 1)(
        DefaultFormFieldsStyle.datePickerLabel,
        MuiFormLabel()(label),
      ),
      MuiGrid(item = true, xs = 11)(
        MuiTextField()(
          DefaultFormFieldsStyle.datePicker,
          ^.`type` := "date",
          ^.defaultValue := SDate(currentDate).toISODateOnly,
          ^.onChange ==> setDate
        )
      ))
  }

  private def exportLinksGroup(props: Props, state: State, gridXs: Int, exports: List[ExportType], title: String): VdomElement =
    MuiGrid(container = true, item = true, spacing = MuiGrid.Spacing.`16`)(
      MuiGrid(item = true, xs = 12)(title),
      exports.map(export =>
        MuiGrid(item = true, xs = gridXs)(
          exportLink(
            props.selectedDate,
            props.terminal.toString,
            export,
            SPAMain.exportDatesUrl(export, state.startDate, state.endDate, props.terminal)
          )
        )
      ).toVdomArray
    )

  def apply(portCode: PortCode, terminal: Terminal, viewMode: ViewMode, selectedDate: SDateLike, loggedInUser: LoggedInUser): VdomElement = component(Props(portCode, terminal, viewMode, selectedDate, loggedInUser: LoggedInUser))
}
