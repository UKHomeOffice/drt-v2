package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.actions.Actions.{RemoveArrivalSources, SetFlightFilterMessage}
import drt.client.components.DropInDialog.StringExtended
import drt.client.components.FlightComponents.SplitsGraph
import drt.client.components.FlightTableRow.SplitsGraphComponentFn
import drt.client.modules.GoogleEventTracker
import drt.client.services._
import drt.shared._
import drt.shared.api.{FlightManifestSummary, WalkTimes}
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import io.kinoplan.scalajs.react.material.ui.core.{MuiInputAdornment, MuiTextField, MuiTypography}
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.{Clear, Search}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.all.onClick
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{Callback, CtorType, _}
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
import scala.scalajs.js.timers._
import scala.scalajs.js.JSConverters._

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

  implicit val reuseProps: Reusability[Props] = Reusability {
    (a, b) =>
      a.viewStart == b.viewStart &&
        a.viewEnd == b.viewEnd &&
        a.filterFlightNumber == b.filterFlightNumber
  }

  var typingSearchTimer: Option[SetTimeoutHandle] = None
  val doneSearchTypingInterval = 5000

  def submitSearchTermToAnalyticsAfterDelay(searchTerm: String, portCode: String): Unit = {
    typingSearchTimer.foreach(clearTimeout)
    if (searchTerm.length > 1) {
      typingSearchTimer = Some(setTimeout(doneSearchTypingInterval) {
        println(s"Sending event for search term $searchTerm")
        Callback(GoogleEventTracker.sendEvent(portCode, "flightNumberSearch", searchTerm))
      })
    }
  }

  implicit class ArrivalOps(val arrival: uk.gov.homeoffice.drt.arrivals.Arrival) extends AnyVal {
    def toFlightArrival: drt.client.components.FlightArrival = {
      drt.client.components.FlightArrival(
        highlights = js.Array("highlight1"),
        flight = arrival.VoyageNumber.toString,
        origin= arrival.Origin.toString,
        country = "",
        gate = arrival.Gate.toString,
        status= arrival.Status.toString,
        scheduled= arrival.Scheduled.toString,
        expected= arrival.Estimated.toString,
        expPcp= arrival.PcpTime.toString,
        expPcpPax= ExpPcpPax("",0),
        paxCounts= PaxCounts("",0,0,0)
//        Operator = arrival.Operator,
//        CarrierCode = arrival.CarrierCode,
//        VoyageNumber = arrival.VoyageNumber,
//        FlightCodeSuffix = arrival.FlightCodeSuffix,
//        Status = arrival.Status,
//        Estimated = arrival.Estimated,
//        Predictions = arrival.Predictions,
//        Actual = arrival.Actual,
//        EstimatedChox = arrival.EstimatedChox,
//        ActualChox = arrival.ActualChox,
//        Gate = arrival.Gate,
//        Stand = arrival.Stand,
//        MaxPax = arrival.MaxPax,
//        RunwayID = arrival.RunwayID,
//        BaggageReclaimId = arrival.BaggageReclaimId,
//        AirportID = arrival.AirportID,
//        Terminal = arrival.Terminal,
//        Origin = arrival.Origin,
//        Scheduled = arrival.Scheduled,
//        PcpTime = arrival.PcpTime,
//        FeedSources = arrival.FeedSources,
//        CarrierScheduled = arrival.CarrierScheduled,
//        ScheduledDeparture = arrival.ScheduledDeparture,
//        RedListPax = arrival.RedListPax,
//        PassengerSources = arrival.PassengerSources
      )
    }
  }
  def apply(shortLabel: Boolean = false,
            originMapper: PortCode => VdomNode = portCode => portCode.toString,
            splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div()
           ): Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ArrivalsTable")
    .renderPS { (_, props, _) =>
      val excludedPaxNote = if (props.redListOriginWorkloadExcluded)
        "* Passengers from CTA & Red List origins do not contribute to PCP workload"
      else
        "* Passengers from CTA origins do not contribute to PCP workload"

      def updateState(value: String): CallbackTo[Unit] = {
        Callback(SPACircuit.dispatch(SetFlightFilterMessage(value)))
      }

      def filterFlights(flights: List[ApiFlightWithSplits],
                        filter: String,
                        airportInfos: Map[PortCode, Pot[AirportInfo]]): List[ApiFlightWithSplits] = {
        flights.filter(f => f.apiFlight.flightCodeString.toLowerCase.contains(filter.toLowerCase)
          || f.apiFlight.Origin.iata.toLowerCase.contains(filter.toLowerCase)
          || airportInfos.get(f.apiFlight.Origin).exists(airportInfo => airportInfo
          .exists(_.country.toLowerCase.contains(filter.toLowerCase))))
      }

      case class Model(flaggedNationalities: Set[Country],
                       portStatePot: Pot[PortState],
                       flightManifestSummaries: Map[ArrivalKey, FlightManifestSummary],
                       arrivalSources: Option[(UniqueArrival, Pot[List[Option[FeedSourceArrival]]])],
                       airportInfos: Map[PortCode, Pot[AirportInfo]]
                      )

      val flaggerConnect = SPACircuit.connect(m => Model(m.flaggedNationalities, m.portStatePot, m.flightManifestSummaries, m.arrivalSources,m.airportInfos))
//      val flightTableContent = FlightTableContent(shortLabel, originMapper, splitsGraphComponent)
//      val flightFlaggerProps = FlightFlaggerProps(
//        nationalities = js.Array("USA", "UK", "India"), // replace with actual nationalities
//        ageGroups = js.Array("0-17", "18-64", "65+"), // replace with actual age groups
//        submitCallback = (payload: SearchFilterPayload) => {
//          // handle submit callback
//        },
//        flights = js.Array(), // replace with actual flights
//        isLoading = false // replace with actual loading state
//      )

      val filterFlightComponent = <.div(^.style := js.Dictionary("padding-right" -> "24px"), MuiTypography(sx = SxProps(Map("font-weight" -> "bold",
        "padding-bottom" -> "10px"
      )))("Search by flight details"),
        MuiTextField(label = "Enter flight details".toVdom, sx = SxProps(Map("minWidth" -> "199px", "font-weight" -> "bold")),
          InputProps = js.Dynamic.literal(
            "style" -> js.Dictionary("backgroundColor" -> "#FFFFFF"),
            "startAdornment" -> MuiInputAdornment(position = "start")(MuiIcons(Search)()).rawNode.asInstanceOf[js.Object],
            "endAdornment" -> MuiInputAdornment(position = "end", sx = SxProps(Map("cursor" -> "pointer", "fontSize" -> "small")))
            (onClick --> updateState(""), MuiIcons(Clear)()).rawNode.asInstanceOf[js.Object]
          ))(^.`type` := "text",
          ^.defaultValue := props.filterFlightNumber,
          ^.autoFocus := true,
          ^.onChange ==> { e: ReactEventFromInput =>
            val searchTerm = e.target.value
            submitSearchTermToAnalyticsAfterDelay(searchTerm, props.portCode.toString)
            updateState(searchTerm)
          })
      )

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
//          <.div(^.id := "showFlagger", ^.style := js.Dictionary("backgroundColor" -> "#E6E9F1", "padding-left" -> "24px", "padding-top" -> "24px", "padding-bottom" -> "24px"),
//            if (props.showFlagger) {
//              <.div(^.style := js.Dictionary("display" -> "flex"),
////                filterFlightComponent,
////                <.div(^.style := js.Dictionary("borderRight" -> "1px solid #000")),
////                <.div(^.style := js.Dictionary("padding-left" -> "24px"),
////                  NationalityFlaggingComponent.component(NationalityFlaggingComponent.Props(model.flaggedNationalities))),
//                <.div(^.style := js.Dictionary("padding-left" -> "24px"),
//                  <.div(FlightFlaggerComponent(flightFlaggerProps))
//                ))
//            } else EmptyVdom
//          ),
          <.div(
            model.portStatePot.render { portState =>
              val flightsForWindow = portState.window(props.viewStart, props.viewEnd, props.paxFeedSourceOrder)
              val flights: Seq[ApiFlightWithSplits] = filterFlights(flightsForWindow.flights.values.toList, props.filterFlightNumber, model.airportInfos)

              val convertedFlights = flights.map { flight =>
                // Assuming there is a method `toFlightArrival` that converts `uk.gov.homeoffice.drt.arrivals.Arrival` to `drt.client.components.FlightArrival`
                flight.apiFlight.toFlightArrival
              }.toJSArray
              FlightFlaggerComponent(FlightFlaggerProps(
                nationalities = model.flaggedNationalities.map(_.threeLetterCode).toJSArray,//js.Array("USA", "UK", "India"), // replace with actual nationalities
                ageGroups = js.Array("0-17", "18-64", "65+"), // replace with actual age groups
                submitCallback = (payload: SearchFilterPayload) => {
                  // handle submit callback
                },
                flights = convertedFlights, // replace with actual flights
                isLoading = false // replace with actual loading state
              ))
//              flightTableContent(
//                FlightTableContent.Props(
//                  portState = portState,
//                  flightManifestSummaries = model.flightManifestSummaries,
//                  queueOrder = props.queueOrder,
//                  hasEstChox = props.hasEstChox,
//                  loggedInUser = props.loggedInUser,
//                  viewMode = props.viewMode,
//                  defaultWalkTime = props.defaultWalkTime,
//                  hasTransfer = props.hasTransfer,
//                  displayRedListInfo = props.displayRedListInfo,
//                  redListOriginWorkloadExcluded = props.redListOriginWorkloadExcluded,
//                  terminal = props.terminal,
//                  portCode = props.portCode,
//                  redListPorts = props.redListPorts,
//                  redListUpdates = props.redListUpdates,
//                  airportConfig = props.airportConfig,
//                  walkTimes = props.walkTimes,
//                  flaggedNationalities = model.flaggedNationalities,
//                  viewStart = props.viewStart,
//                  viewEnd = props.viewEnd,
//                  paxFeedSourceOrder = props.paxFeedSourceOrder,
//                  filterFlightNumber = props.filterFlightNumber
//                ))
            }
          ),
          excludedPaxNote
        )
      }
    }
    .configure(Reusability.shouldComponentUpdate)
    .build
}
