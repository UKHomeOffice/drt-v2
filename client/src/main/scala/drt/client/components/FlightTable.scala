package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.actions.Actions.RemoveArrivalSources
import drt.client.components.FlightComponents.SplitsGraph
import drt.client.components.FlightTableRow.SplitsGraphComponentFn
import drt.client.services._
import drt.shared._
import drt.shared.api.{FlightManifestSummary, WalkTimes}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{CtorType, _}
import uk.gov.homeoffice.drt.arrivals.UniqueArrival
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.ArrivalSource
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, PortCode}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.immutable.HashSet

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
                  ) extends UseValueEq

  implicit val reuseProps: Reusability[Props] = Reusability {
    (a, b) =>
      a.viewStart == b.viewStart &&
        a.viewEnd == b.viewEnd
  }

  def apply(shortLabel: Boolean = false,
            originMapper: PortCode => VdomNode = portCode => portCode.toString,
            splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div()
           ): Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ArrivalsTable")
    .render_PS { (props, _) =>
      val excludedPaxNote = if (props.redListOriginWorkloadExcluded)
        "* Passengers from CTA & Red List origins do not contribute to PCP workload"
      else
        "* Passengers from CTA origins do not contribute to PCP workload"

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
                <.div(^.className := "dashboard-arrivals-popup", ArrivalInfo.SourcesTable(ArrivalInfo.Props(sourcesPot, props.airportConfig, props.paxFeedSourceOrder)))
              )
            case _ => <.div()
          },
          <.div(
            if (props.showFlagger) NationalityFlaggingComponent.component(NationalityFlaggingComponent.Props(model.flaggedNationalities)) else EmptyVdom,
            <.div(
              model.portStatePot.render { portState =>
                flightTableContent(
                  FlightTableContent.Props(
                    portState,
                    model.flightManifestSummaries,
                    props.queueOrder,
                    props.hasEstChox,
                    props.loggedInUser,
                    props.viewMode,
                    props.defaultWalkTime,
                    props.hasTransfer,
                    props.displayRedListInfo,
                    props.redListOriginWorkloadExcluded,
                    props.terminal,
                    props.portCode,
                    props.redListPorts,
                    props.redListUpdates,
                    props.airportConfig,
                    props.walkTimes,
                    model.flaggedNationalities,
                    props.viewStart,
                    props.viewEnd,
                    props.paxFeedSourceOrder,
                  ))
              }
            ),
            excludedPaxNote
          )
        )
      }
    }
    .configure(Reusability.shouldComponentUpdate)
    .build
}
