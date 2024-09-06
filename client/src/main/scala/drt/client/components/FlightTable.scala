package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.actions.Actions.{RemoveArrivalSources, UpdateFlightHighlight}
import drt.client.components.FlightComponents.SplitsGraph
import drt.client.components.FlightTableRow.SplitsGraphComponentFn
import drt.client.components.styles.DrtTheme.buttonSecondaryTheme
import drt.client.modules.GoogleEventTracker
import drt.client.services._
import drt.shared._
import drt.shared.api.{AgeRange, FlightManifestSummary, PaxAgeRange, WalkTimes}
import io.kinoplan.scalajs.react.material.ui.core.system.ThemeProvider
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.vdom.html_<^
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{Callback, _}
import org.scalajs.dom.html.Div
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.ArrivalSource
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, PortCode}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.immutable.HashSet
import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.util.Try

object FlightTable {
  case class Props(queueOrder: Seq[Queue],
                   hasEstChox: Boolean,
                   loggedInUser: LoggedInUser,
                   viewMode: ViewMode,
                   defaultWalkTime: Long,
                   hasTransfer: Boolean,
                   displayRedListInfo: Boolean,
                   redListOriginWorkloadExcluded: Boolean,
                   terminal: Terminal,
                   portCode: PortCode,
                   redListPorts: HashSet[PortCode],
                   redListUpdates: RedListUpdates,
                   airportConfig: AirportConfig,
                   walkTimes: WalkTimes,
                   viewStart: SDateLike,
                   viewEnd: SDateLike,
                   showFlagger: Boolean,
                   paxFeedSourceOrder: List[FeedSource],
                   flightHighlight: FlightHighlight,
                   flights: Pot[Seq[ApiFlightWithSplits]],
                   airportInfos: Map[PortCode, Pot[AirportInfo]],
                   flightManifestSummaries: Map[ArrivalKey, FlightManifestSummary],
                   arrivalSources: Option[(UniqueArrival, Pot[List[Option[FeedSourceArrival]]])],
                   originMapper: (PortCode, html_<^.TagMod) => VdomNode,
                   splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div(),
                  ) extends UseValueEq

  case class State(showHighlightedRows: Boolean)

  implicit val reuseProps: Reusability[Props] = Reusability {
    (a, b) =>
      a.viewStart == b.viewStart &&
        a.viewEnd == b.viewEnd &&
        a.flights == b.flights &&
        a.flightHighlight == b.flightHighlight
  }

  implicit val stateReuse: Reusability[State] = Reusability.always

  val ageGroups: js.Array[String] =
    js.Array(AgeRange(0, 9).title,
      AgeRange(10, 24).title,
      AgeRange(25, 49).title,
      AgeRange(50, 65).title,
      AgeRange(65, None).title)

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
              showHighlightedRows = false,
              selectedAgeGroups = Seq(),
              selectedNationalities = Set(),
              filterFlightSearch = "")))
      }

      val onChangeInput: js.Function1[Object, Unit] = (event: Object) => {
        val searchTerm = event.asInstanceOf[String]
        updateState(searchTerm)
      }

      <.div(
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
            ThemeProvider(buttonSecondaryTheme)(
              FlightFlaggerFilters(
                CountryOptions.countries.map { c => CountryJS(c.name, c.threeLetterCode) }.toJSArray,
                ageGroups,
                submitCallback,
                showAllCallback,
                clearFiltersCallback,
                onChangeInput,
                initialState
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
              defaultWalkTime = props.defaultWalkTime,
              hasTransfer = props.hasTransfer,
              displayRedListInfo = props.displayRedListInfo,
              redListOriginWorkloadExcluded = props.redListOriginWorkloadExcluded,
              terminal = props.terminal,
              portCode = props.portCode,
              redListPorts = props.redListPorts,
              redListUpdates = props.redListUpdates,
              airportConfig = props.airportConfig,
              walkTimes = props.walkTimes,
              flaggedNationalities = props.flightHighlight.selectedNationalities,
              flaggedAgeGroups = props.flightHighlight.selectedAgeGroups.map(PaxAgeRange.parse).toSet,
              showNumberOfVisaNationals = props.flightHighlight.showNumberOfVisaNationals,
              showOnlyHighlightedRows = state.showHighlightedRows,
              showRequireAllSelected = props.flightHighlight.showRequireAllSelected,
              viewStart = props.viewStart,
              viewEnd = props.viewEnd,
              paxFeedSourceOrder = props.paxFeedSourceOrder,
              showFlagger = props.showFlagger,
              originMapper = props.originMapper,
              splitsGraphComponent = props.splitsGraphComponent,
              airportInfos = props.airportInfos,
            )
          )
        },
        excludedPaxNote
      )
    }
  }

  val component = ScalaComponent.builder[Props]("ArrivalsTable")
    .initialStateFromProps(p => State(p.flightHighlight.showHighlightedRows))
    .renderBackend[Backend]
//    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(props: Props): Unmounted[Props, State, Backend] = component(props)
}
