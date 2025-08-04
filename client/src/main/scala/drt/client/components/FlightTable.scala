package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.SPAMain.TerminalPageTabLoc
import drt.client.actions.Actions.{RemoveArrivalSources, UpdateFlightHighlight}
import drt.client.components.styles.DrtReactTheme
import drt.client.modules.GoogleEventTracker
import drt.client.services._
import drt.shared._
import drt.shared.api.WalkTimes
import io.kinoplan.scalajs.react.material.ui.core.MuiTypography
import io.kinoplan.scalajs.react.material.ui.core.system.ThemeProvider
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.vdom.html_<^
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{Callback, CtorType, _}
import org.scalajs.dom.html.Div
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.ArrivalSource
import uk.gov.homeoffice.drt.models.{AgeRange, FlightManifestSummary, ManifestKey, UserPreferences}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, PortCode}
import uk.gov.homeoffice.drt.redlist.RedListUpdates

import scala.collection.immutable.HashSet
import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.util.Try

object FlightTable {
  case class Props(queueOrder: Seq[Queue],
                   hasEstChox: Boolean,
                   loggedInUser: LoggedInUser,
                   viewMode: ViewMode,
                   hasTransfer: Boolean,
                   displayRedListInfo: Boolean,
                   redListOriginWorkloadExcluded: Boolean,
                   terminal: Terminal,
                   portCode: PortCode,
                   redListPorts: HashSet[PortCode],
                   redListUpdates: RedListUpdates,
                   airportConfig: AirportConfig,
                   walkTimes: WalkTimes,
                   showFlagger: Boolean,
                   paxFeedSourceOrder: List[FeedSource],
                   flightHighlight: FlightHighlight,
                   flights: Pot[Seq[ApiFlightWithSplits]],
                   flightManifestSummaries: Map[ManifestKey, FlightManifestSummary],
                   arrivalSources: Option[(UniqueArrival, Pot[List[Option[FeedSourceArrival]]])],
                   originMapper: (PortCode, Option[PortCode], html_<^.TagMod) => VdomNode,
                   userPreferences: UserPreferences,
                   terminalPageTab: TerminalPageTabLoc
                  ) extends UseValueEq

  case class State(showHighlightedRows: Boolean)

  val ageGroups: js.Array[String] =
    js.Array(
      AgeRange(0, 9).title,
      AgeRange(10, 24).title,
      AgeRange(25, 49).title,
      AgeRange(50, 65).title,
      AgeRange(65, None).title,
    )

  class Backend(scope: BackendScope[Props, State]) {
    def render(props: Props, state: State): VdomTagOf[Div] = {
      val excludedPaxNote = if (props.redListOriginWorkloadExcluded)
        "* Passengers from CTA & Red List origins do not contribute to PCP workload"
      else
        "* Passengers from CTA origins do not contribute to PCP workload"

      def updateState(searchTerm: String): Unit = {
        SPACircuit.dispatch(
          UpdateFlightHighlight(
            FlightHighlight(
              props.flightHighlight.showNumberOfVisaNationals,
              props.flightHighlight.showRequireAllSelected,
              state.showHighlightedRows,
              props.flightHighlight.selectedAgeGroups.toSeq,
              props.flightHighlight.selectedNationalities,
              searchTerm)))
      }


      val submitCallback: js.Function1[js.Object, Unit] = (data: js.Object) => {
        val sfp = data.asInstanceOf[SearchFilterPayload]
        val selectedNationalities: Set[Country] = CountryOptions.countries.filter(c => sfp.selectedNationalities.map(_.code).contains(c.threeLetterCode)).toSet
        val highlight = FlightHighlight(
          sfp.showNumberOfVisaNationals,
          sfp.requireAllSelected,
          state.showHighlightedRows,
          sfp.selectedAgeGroups.toSeq,
          selectedNationalities,
          props.flightHighlight.filterFlightSearch)

        SPACircuit.dispatch(UpdateFlightHighlight(highlight))

        Callback(GoogleEventTracker.sendEvent(props.terminal.toString, "flightFilter", sfp.toString))
      }

      val showAllCallback: js.Function1[js.Object, Unit] = (event: js.Object) => {
        val radioButtonValue: Boolean = Try(event.asInstanceOf[ReactEventFromInput].target.value.toBoolean).getOrElse(false)
        scope.modState(s => s.copy(showHighlightedRows = radioButtonValue)).runNow()
        SPACircuit.dispatch(
          UpdateFlightHighlight(
            FlightHighlight(
              props.flightHighlight.showNumberOfVisaNationals,
              props.flightHighlight.showRequireAllSelected,
              radioButtonValue,
              props.flightHighlight.selectedAgeGroups.toSeq,
              props.flightHighlight.selectedNationalities,
              props.flightHighlight.filterFlightSearch)))
      }

      val clearFiltersCallback: js.Function1[js.Object, Unit] = (_: js.Object) => {
        SPACircuit.dispatch(
          UpdateFlightHighlight(
            FlightHighlight(
              showNumberOfVisaNationals = false,
              showRequireAllSelected = false,
              showOnlyHighlightedRows = false,
              selectedAgeGroups = Seq(),
              selectedNationalities = Set(),
              filterFlightSearch = "")))
      }

      val onChangeInput: js.Function1[Object, Unit] = (event: Object) => {
        val searchTerm = event.asInstanceOf[String]
        updateState(searchTerm)
      }

      <.div(^.className := "arrivals-title",
        MuiTypography(variant = "h2")(s"Arrivals"),
        (props.loggedInUser.hasRole(ArrivalSource), props.arrivalSources) match {
          case (true, Some((_, sourcesPot))) =>
            <.div(^.tabIndex := 0,
              <.div(^.className := "popover-overlay", ^.onClick --> Callback(SPACircuit.dispatch(RemoveArrivalSources))),
              <.div(^.className := "dashboard-arrivals-popup",
                ArrivalInfo.SourcesTable(ArrivalInfo.Props(sourcesPot, props.airportConfig, props.paxFeedSourceOrder)))
            )
          case _ => <.div()
        },
        <.div(
          ^.style := js.Dictionary(
            "backgroundColor" -> "#E6E9F1",
            "paddingLeft" -> "24px",
            "paddingTop" -> "36px",
            "paddingBottom" -> "24px",
            "paddingRight" -> "24px"
          ),
          if (props.showFlagger) {
            val initialState = js.Dynamic.literal(
              showNumberOfVisaNationals = props.flightHighlight.showNumberOfVisaNationals,
              requireAllSelected = props.flightHighlight.showRequireAllSelected,
              flightNumber = props.flightHighlight.filterFlightSearch,
              selectedNationalities = props.flightHighlight.selectedNationalities.map(n => CountryJS(n.name, n.threeLetterCode)).toJSArray,
              selectedAgeGroups = props.flightHighlight.selectedAgeGroups.toJSArray,
            )
            ThemeProvider(DrtReactTheme)(
              FlightFlaggerFilters(
                props.terminal.toString,
                CountryOptions.countries.map { c => CountryJS(c.name, c.threeLetterCode) }.toJSArray,
                ageGroups,
                submitCallback,
                showAllCallback,
                clearFiltersCallback,
                onChangeInput,
                initialState,
                GoogleEventTracker.sendEvent
              ))
          } else EmptyVdom
        ),
        <.div {
          FlightTableContent(
            FlightTableContent.Props(
              flights = props.flights,
              flightManifestSummaries = props.flightManifestSummaries,
              queueOrder = props.queueOrder,
              hasEstChox = props.hasEstChox,
              loggedInUser = props.loggedInUser,
              viewMode = props.viewMode,
              hasTransfer = props.hasTransfer,
              displayRedListInfo = props.displayRedListInfo,
              terminal = props.terminal,
              portCode = props.portCode,
              redListPorts = props.redListPorts,
              redListUpdates = props.redListUpdates,
              airportConfig = props.airportConfig,
              walkTimes = props.walkTimes,
              paxFeedSourceOrder = props.paxFeedSourceOrder,
              originMapper = props.originMapper,
              flightHighlight = props.flightHighlight,
              userPreferences = props.userPreferences,
              terminalPageTab = props.terminalPageTab
            )
          )
        },
        excludedPaxNote
      )
    }
  }

  val component: Component[Props, State, Backend, CtorType.Props] =
    ScalaComponent.builder[Props]("ArrivalsTable")
      .initialStateFromProps(p => State(p.flightHighlight.showOnlyHighlightedRows))
      .renderBackend[Backend]
      .build

  def apply(props: Props): Unmounted[Props, State, Backend] = component(props)
}
