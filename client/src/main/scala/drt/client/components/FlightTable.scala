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
import io.kinoplan.scalajs.react.material.ui.core.{MuiGrid, MuiInputAdornment, MuiTextField, MuiTypography}
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.{Clear, Search}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.all.onClick
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

  case class State(filterFlightNumber: String)

  implicit val reuseProps: Reusability[Props] = Reusability {
    (a, b) =>
      a.viewStart == b.viewStart &&
        a.viewEnd == b.viewEnd
  }

  implicit val stateReuse: Reusability[State] = Reusability.by(_.filterFlightNumber)

  def apply(shortLabel: Boolean = false,
            originMapper: PortCode => VdomNode = portCode => portCode.toString,
            splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div()
           ): Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ArrivalsTable")
    .initialStateFromProps(p => State(p.filterFlightNumber))
    .renderPS { (scope, props, state) =>
      val excludedPaxNote = if (props.redListOriginWorkloadExcluded)
        "* Passengers from CTA & Red List origins do not contribute to PCP workload"
      else
        "* Passengers from CTA origins do not contribute to PCP workload"

      def updateState(value: String): CallbackTo[Unit] = {
        Callback(SPACircuit.dispatch(SetFlightFilterMessage(value))) >>
          Callback(if (value.nonEmpty) GoogleEventTracker.sendEvent(props.airportConfig.portCode.toString, "flightNumberSearch", value)) >>
          scope.modState(_.copy(filterFlightNumber = value))
      }

      case class Model(flaggedNationalities: Set[Country],
                       portStatePot: Pot[PortState],
                       flightManifestSummaries: Map[ArrivalKey, FlightManifestSummary],
                       arrivalSources: Option[(UniqueArrival, Pot[List[Option[FeedSourceArrival]]])]
                      )

      val flaggerConnect = SPACircuit.connect(m => Model(m.flaggedNationalities, m.portStatePot, m.flightManifestSummaries, m.arrivalSources))
      val flightTableContent = FlightTableContent(shortLabel, originMapper, splitsGraphComponent)

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
            MuiGrid(container = true, sx = SxProps(Map("backgroundColor" -> "#E6E9F1", "min-height" -> "122px", "padding" -> "24px")))(
              MuiGrid(item = true, xs = 2, sx = SxProps(Map("borderRight" -> "1px solid #000")))(
                <.div(^.style := js.Dictionary("display" -> "flex", "flexDirection" -> "column", "padding-right" -> "24px"),
                  MuiTypography(sx = SxProps(Map("font-weight" -> "bold", "padding-bottom" -> "10px")))("Search by flight number"),
                  MuiTextField(label = "Enter flight number".toVdom, sx = SxProps(Map("font-weight" -> "bold")),
                    InputProps = js.Dynamic.literal(
                      "style" -> js.Dictionary("backgroundColor" -> "#FFFFFF"),
                      "startAdornment" -> MuiInputAdornment(position = "start")(
                        (MuiIcons(Search)())
                      ).rawNode.asInstanceOf[js.Object],
                      "endAdornment" -> MuiInputAdornment(position = "end", sx = SxProps(Map("cursor" -> "pointer")))
                      (onClick --> updateState(""),
                        (MuiIcons(Clear)())
                      ).rawNode.asInstanceOf[js.Object]
                    ))(^.`type` := "text",
                    ^.defaultValue := state.filterFlightNumber,
                    ^.autoFocus := true,
                    ^.onChange ==> { e: ReactEventFromInput =>
                      val value = e.target.value
                      updateState(value)
                    })
                ),
              ),
              MuiGrid(item = true, xs = 10)(
                <.div(^.style := js.Dictionary("paddingLeft" -> "24px"),
                  if (props.showFlagger) NationalityFlaggingComponent.component(NationalityFlaggingComponent.Props(model.flaggedNationalities))
                  else EmptyVdom)
              ))),
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
                  flaggedNationalities = model.flaggedNationalities,
                  viewStart = props.viewStart,
                  viewEnd = props.viewEnd,
                  paxFeedSourceOrder = props.paxFeedSourceOrder,
                  filterFlightNumber = state.filterFlightNumber
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
