package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.actions.Actions.{RemoveArrivalSources, UpdateFlightHighlight}
import drt.client.components.FlightComponents.SplitsGraph
import drt.client.components.FlightTableRow.SplitsGraphComponentFn
import drt.client.modules.GoogleEventTracker
import drt.client.services._
import drt.shared._
import drt.shared.api.{AgeRange, FlightManifestSummary, PaxAgeRange, WalkTimes}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{Callback, CtorType, _}
import uk.gov.homeoffice.drt.arrivals.UniqueArrival
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
                  ) extends UseValueEq

  case class State(flightSearch: String, showHighlightedRows: Boolean)

  implicit val reuseProps: Reusability[Props] = Reusability {
    (a, b) =>
      a.viewStart == b.viewStart &&
        a.viewEnd == b.viewEnd &&
        a.flightHighlight.selectedNationalities == b.flightHighlight.selectedNationalities &&
        a.flightHighlight.selectedAgeGroups == b.flightHighlight.selectedAgeGroups &&
        a.flightHighlight.showNumberOfVisaNationals == b.flightHighlight.showNumberOfVisaNationals &&
        a.flightHighlight.showRequireAllSelected == b.flightHighlight.showRequireAllSelected
  }

  implicit val stateReuse: Reusability[State] = Reusability.always

  val ageGroups: js.Array[String] =
    js.Array(AgeRange(0, 9).title,
      AgeRange(10, 24).title,
      AgeRange(25, 49).title,
      AgeRange(50, 65).title,
      AgeRange(65, None).title)


  def apply(shortLabel: Boolean = false,
            originMapper: (PortCode, html_<^.TagMod) => VdomNode, // = portCode => portCode.toString,
            splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div()
           ): Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ArrivalsTable")
    .initialStateFromProps(p => State(p.flightHighlight.filterFlightSearch, p.flightHighlight.showHighlightedRows))
    .renderPS { (scope, props, _) =>
      val excludedPaxNote = if (props.redListOriginWorkloadExcluded)
        "* Passengers from CTA & Red List origins do not contribute to PCP workload"
      else
        "* Passengers from CTA origins do not contribute to PCP workload"

      def updateState(searchTerm: String) {
        SPACircuit.dispatch(
          UpdateFlightHighlight(
            FlightHighlight(
              props.flightHighlight.showNumberOfVisaNationals,
              props.flightHighlight.showRequireAllSelected,
              scope.state.showHighlightedRows,
              props.flightHighlight.selectedAgeGroups.toSeq,
              props.flightHighlight.selectedNationalities,
              searchTerm)))
      }

      case class Model(portStatePot: Pot[PortState],
                       flightManifestSummaries: Map[ArrivalKey, FlightManifestSummary],
                       arrivalSources: Option[(UniqueArrival, Pot[List[Option[FeedSourceArrival]]])],
                       airportInfos: Map[PortCode, Pot[AirportInfo]]
                      )

      val flaggerConnect = SPACircuit.connect(m => Model(m.portStatePot, m.flightManifestSummaries, m.arrivalSources, m.airportInfos))
      val flightTableContent = FlightTableContent(shortLabel, originMapper, splitsGraphComponent)

      val submitCallback: js.Function1[js.Object, Unit] = (data: js.Object) => {
        val sfp = data.asInstanceOf[SearchFilterPayload]
        val selectedNationalities: Set[Country] = CountryOptions.countries.filter(c => sfp.selectedNationalities.map(_.code).contains(c.threeLetterCode)).toSet
        SPACircuit.dispatch(
          UpdateFlightHighlight(
            FlightHighlight(
              sfp.showNumberOfVisaNationals,
              sfp.requireAllSelected,
              scope.state.showHighlightedRows,
              sfp.selectedAgeGroups.toSeq,
              selectedNationalities,
              scope.state.flightSearch)))
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
              scope.state.flightSearch)))
      }

      val clearFiltersCallback: js.Function1[js.Object, Unit] = (data: js.Object) => {
        scope.modState(s => s.copy(showHighlightedRows = false, flightSearch = "")).runNow()
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
        scope.modState(s => s.copy(flightSearch = searchTerm)).runNow()
        updateState(searchTerm)
      }

      flaggerConnect { flaggerProxy =>
        val model = flaggerProxy()
        <.div(
          (props.loggedInUser.hasRole(ArrivalSource), model.arrivalSources) match {
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
                selectedAgeGroups = props.flightHighlight.selectedAgeGroups.map(AutocompleteOption(_)).toJSArray,
                selectedNationalities = props.flightHighlight.selectedNationalities.map(n => CountryJS(n.name,n.threeLetterCode)).toJSArray,
                flightNumber = props.flightHighlight.filterFlightSearch,
                requireAllSelected = props.flightHighlight.showRequireAllSelected
              )

              FlightFlaggerFilters(
                CountryOptions.countries.map { c => CountryJS(c.name,c.threeLetterCode)}.toJSArray,
                ageGroups,
                submitCallback,
                showAllCallback,
                clearFiltersCallback,
                onChangeInput,
                initialState
              )
            } else EmptyVdom
          ),
          <.div(
            model.portStatePot.render { portState =>
              flightTableContent(
                FlightTableContent.Props(
                  portState = portState,
                  flightManifestSummaries = model.flightManifestSummaries,
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
                  showHighlightedRows = scope.state.showHighlightedRows,
                  showRequireAllSelected = props.flightHighlight.showRequireAllSelected,
                  viewStart = props.viewStart,
                  viewEnd = props.viewEnd,
                  paxFeedSourceOrder = props.paxFeedSourceOrder,
                  filterFlightSearch = scope.state.flightSearch,
                  showFlagger = props.showFlagger,
                ))
            }
          ),
          excludedPaxNote
        )
      }
    }
    .configure(Reusability.shouldComponentUpdate)
    .build
}
