package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.actions.Actions.{RemoveArrivalSources, SetFlightFilterMessage}
import drt.client.components.DaySelectorComponent.State
import drt.client.components.DropInDialog.StringExtended
import drt.client.components.FlightChartComponent.State
import drt.client.components.FlightComponents.SplitsGraph
import drt.client.components.FlightTableRow.SplitsGraphComponentFn
import drt.client.modules.GoogleEventTracker
import drt.client.services._
import drt.shared._
import drt.shared.api.{AgeRange, FlightManifestSummary, PaxAgeRange, WalkTimes}
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import io.kinoplan.scalajs.react.material.ui.core.{MuiInputAdornment, MuiTextField, MuiTypography}
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.{Clear, Search}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.all.onClick
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{Callback, CtorType, _}
import org.scalajs.dom.HTMLInputElement
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
import scala.scalajs.js.timers._
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
                   filterFlightNumber: String) extends UseValueEq

  case class State(showTransitPaxNumber: Boolean,
                   showNumberOfVisaNationals: Boolean,
                   showRequireAllSelected: Boolean,
                   showHighlightedRows: Boolean,
                   selectedAgeGroups: Seq[String],
                   selectedNationalities: Seq[Country],
                   flightNumber: String)

  implicit val reuseProps: Reusability[Props] = Reusability {
    (a, b) =>
      a.viewStart == b.viewStart &&
        a.viewEnd == b.viewEnd &&
        a.filterFlightNumber == b.filterFlightNumber
  }

  implicit val stateReuse: Reusability[State] = Reusability.always


//    implicit val stateReuse: Reusability[State] = Reusability((a, b) => a.showTransitPaxNumber == b.showTransitPaxNumber &&
//    a.showNumberOfVisaNationals == b.showNumberOfVisaNationals &&
//    a.showHighlightedRows == b.showHighlightedRows &&
//    a.showRequireAllSelected == b.showRequireAllSelected &&
////    a.flightNumber == b.flightNumber &&
//    a.selectedAgeGroups == b.selectedAgeGroups &&
//    a.selectedNationalities == b.selectedNationalities
//  )


  var typingSearchTimer: Option[SetTimeoutHandle] = None
  val doneSearchTypingInterval = 5000

  //  def submitSearchTermToAnalyticsAfterDelay(searchTerm: String, portCode: String): Unit = {
  //    typingSearchTimer.foreach(clearTimeout)
  //    if (searchTerm.length > 1) {
  //      typingSearchTimer = Some(setTimeout(doneSearchTypingInterval) {
  //        println(s"Sending event for search term $searchTerm")
  ////        scope.modState(s => s.copy(flightNumber = searchTerm)) //.runNow()
  //        Callback(GoogleEventTracker.sendEvent(portCode, "flightNumberSearch", searchTerm))
  //      })
  //    }
  //  }

  val ageGroups: js.Array[String] = js.Array(AgeRange(0, 9).title, AgeRange(10, 24).title, AgeRange(25, 49).title, AgeRange(50, 65).title, AgeRange(65, 120).title)


  def apply(shortLabel: Boolean = false,
            originMapper: PortCode => VdomNode = portCode => portCode.toString,
            splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div()
           ): Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ArrivalsTable")
    .initialStateFromProps(p => State(false, false, false, false, Seq.empty, Seq.empty, p.filterFlightNumber))
    .renderPS { (scope, props, state) =>
      val excludedPaxNote = if (props.redListOriginWorkloadExcluded)
        "* Passengers from CTA & Red List origins do not contribute to PCP workload"
      else
        "* Passengers from CTA origins do not contribute to PCP workload"

      def updateState(value: String): CallbackTo[Unit] = {
        Callback(SPACircuit.dispatch(SetFlightFilterMessage(value)))
      }

      case class Model( //flaggedNationalities: Set[Country],
                        portStatePot: Pot[PortState],
                        flightManifestSummaries: Map[ArrivalKey, FlightManifestSummary],
                        arrivalSources: Option[(UniqueArrival, Pot[List[Option[FeedSourceArrival]]])],
                        airportInfos: Map[PortCode, Pot[AirportInfo]]
                      )

      val flaggerConnect = SPACircuit.connect(m => Model( //m.flaggedNationalities,
        m.portStatePot, m.flightManifestSummaries, m.arrivalSources, m.airportInfos))
      val flightTableContent = FlightTableContent(shortLabel, originMapper, splitsGraphComponent)

      val submitCallback: js.Function1[js.Object, Unit] = (data: js.Object) => {
        // Handle the submit action here
        val sfp = data.asInstanceOf[SearchFilterPayload]
        val countriesCode: Seq[String] = sfp.selectedNationalities.map(_.split("\\(").last.split("\\)").head).toSeq
        val selectedNationalities: Seq[Country] = CountryOptions.countries.filter(c => countriesCode.contains(c.threeLetterCode))
        scope.modState(_.copy(
          showTransitPaxNumber = sfp.showTransitPaxNumber,
          showNumberOfVisaNationals  = sfp.showNumberOfVisaNationals,
          showRequireAllSelected =sfp.requireAllSelected,
          false,
          selectedAgeGroups = sfp.selectedAgeGroups.toSeq,
          selectedNationalities =selectedNationalities,
          flightNumber=sfp.flightNumber)).runNow()
        //        updateState(sfp.flightNumber)
        println(s"showCallback event: ${countriesCode} ${sfp.selectedAgeGroups} ${sfp.selectedNationalities} ${sfp.flightNumber}")

      }

      val showAllCallback: js.Function1[js.Object, Unit] = (event: js.Object) => {
        // Handle the show all action here
        val radioButtonValue = event.asInstanceOf[ReactEventFromInput].target.value
        scope.modState(s => s.copy(showHighlightedRows = Try(radioButtonValue.toBoolean).getOrElse(false))).runNow()
        println(s"Radio button value: $radioButtonValue")

      }

      val onChangeInput: js.Function1[js.Object, Unit] = (event: js.Object) => {
        val searchTerm = event.asInstanceOf[String]
        scope.modState(s => s.copy(flightNumber = searchTerm)).runNow()
        println(s"onChange event: $searchTerm ${scope.state.flightNumber}")
      }


      //      val filterFlightComponent = <.div(^.style := js.Dictionary("padding-right" -> "24px"), MuiTypography(sx = SxProps(Map("font-weight" -> "bold",
      //        "padding-bottom" -> "10px"
      //      )))("Search by flight details"),
      //        MuiTextField(label = "Enter flight details".toVdom, sx = SxProps(Map("minWidth" -> "199px", "font-weight" -> "bold")),
      //          InputProps = js.Dynamic.literal(
      //            "style" -> js.Dictionary("backgroundColor" -> "#FFFFFF"),
      //            "startAdornment" -> MuiInputAdornment(position = "start")(MuiIcons(Search)()).rawNode.asInstanceOf[js.Object],
      //            "endAdornment" -> MuiInputAdornment(position = "end", sx = SxProps(Map("cursor" -> "pointer", "fontSize" -> "small")))
      //            (onClick --> updateState(""), MuiIcons(Clear)()).rawNode.asInstanceOf[js.Object]
      //          ))(^.`type` := "text",
      //          ^.defaultValue := props.filterFlightNumber,
      //          ^.autoFocus := true,
      //          ^.onChange ==> { e: ReactEventFromInput =>
      //            val searchTerm = e.target.value
      //            submitSearchTermToAnalyticsAfterDelay(searchTerm, props.portCode.toString)
      //            updateState(searchTerm)
      //          })
      //      )

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
          <.div(^.style := js.Dictionary("backgroundColor" -> "#E6E9F1", "padding-left" -> "24px", "padding-top" -> "24px", "padding-bottom" -> "24px"),
            if (props.showFlagger) {
              FlightFlaggerFilters(
                CountryOptions.countries.map { c => s"${c.name} (${c.threeLetterCode})" }.toJSArray,
                ageGroups,
                submitCallback,
                showAllCallback,
                onChangeInput
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
                  flaggedNationalities = scope.state.selectedNationalities.toSet,
                  flaggedAgeGroups = scope.state.selectedAgeGroups.map(PaxAgeRange.parse).toSet,
                  showTransitPaxNumber = scope.state.showTransitPaxNumber,
                  showNumberOfVisaNationals = scope.state.showNumberOfVisaNationals,
                  showHighlightedRows = scope.state.showHighlightedRows,
                  showRequireAllSelected = scope.state.showRequireAllSelected,
                  viewStart = props.viewStart,
                  viewEnd = props.viewEnd,
                  paxFeedSourceOrder = props.paxFeedSourceOrder,
                  filterFlightNumber = scope.state.flightNumber,
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
