package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.actions.Actions.RemoveArrivalSources
import drt.client.components.FlightComponents.SplitsGraph
import drt.client.components.FlightTableRow.SplitsGraphComponentFn
import drt.client.components.ToolTips._
import drt.client.services._
import drt.shared._
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.vdom.{TagMod, TagOf}
import japgolly.scalajs.react.{CtorType, _}
import org.scalajs.dom
import org.scalajs.dom.html.TableSection
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, UniqueArrival}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.ArrivalSource
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, PortCode, Queues}
import uk.gov.homeoffice.drt.redlist.RedListUpdates

import scala.collection.immutable.HashSet

object FlightTable {
  case class Props(flightsWithSplits: List[ApiFlightWithSplits],
                   queueOrder: Seq[Queue],
                   hasEstChox: Boolean,
                   arrivalSources: Option[(UniqueArrival, Pot[List[Option[FeedSourceArrival]]])],
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
                  ) extends UseValueEq

  case class State(apiDataLoaded: Boolean)

  def ArrivalsTable(shortLabel: Boolean = false,
                    timelineComponent: Option[Arrival => VdomNode] = None,
                    originMapper: PortCode => VdomNode = portCode => portCode.toString,
                    splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div()
                   ): Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ArrivalsTable")
    .render_P { props =>
      val flightsWithSplits = props.flightsWithSplits
      val flightsWithCodeShares: Seq[(ApiFlightWithSplits, Set[Arrival])] = FlightTableComponents.uniqueArrivalsWithCodeShares(flightsWithSplits)
      val sortedFlights = flightsWithCodeShares.sortBy(_._1.apiFlight.PcpTime)
      val isTimeLineSupplied = timelineComponent.isDefined
      val timelineTh = (if (isTimeLineSupplied) <.th("Timeline") :: Nil else List[TagMod]()).toTagMod

      if (sortedFlights.nonEmpty) {
        val redListPaxExist = sortedFlights.exists(_._1.apiFlight.RedListPax.exists(_ > 0))
        val excludedPaxNote = if (props.redListOriginWorkloadExcluded)
          "* Passengers from CTA & Red List origins do not contribute to PCP workload"
        else
          "* Passengers from CTA origins do not contribute to PCP workload"

        <.div(
          (props.loggedInUser.hasRole(ArrivalSource), props.arrivalSources) match {
            case (true, Some((_, sourcesPot))) =>
              <.div(^.tabIndex := 0,
                <.div(^.className := "popover-overlay", ^.onClick --> Callback(SPACircuit.dispatch(RemoveArrivalSources))),
                <.div(^.className := "dashboard-arrivals-popup", ArrivalInfo.SourcesTable(ArrivalInfo.Props(sourcesPot, props.airportConfig)))
              )
            case _ => <.div()
          },
          <.table(
            ^.className := "arrivals-table table-striped",
            tableHead(props, timelineTh, props.queueOrder, redListPaxExist, shortLabel),
            <.tbody(
              sortedFlights.zipWithIndex.map {
                case ((flightWithSplits, codeShares), idx) =>
                  val isRedListOrigin = props.redListPorts.contains(flightWithSplits.apiFlight.Origin)
                  val directRedListFlight = redlist.DirectRedListFlight(props.viewMode.dayEnd.millisSinceEpoch, props.portCode, props.terminal, flightWithSplits.apiFlight.Terminal, isRedListOrigin)
                  val redListPaxInfo = redlist.IndirectRedListPax(props.displayRedListInfo, flightWithSplits)
                  FlightTableRow.component(FlightTableRow.Props(
                    flightWithSplits = flightWithSplits,
                    codeShares = codeShares,
                    idx = idx,
                    timelineComponent = timelineComponent,
                    originMapper = originMapper,
                    splitsGraphComponent = splitsGraphComponent,
                    splitsQueueOrder = props.queueOrder,
                    hasEstChox = props.hasEstChox,
                    loggedInUser = props.loggedInUser,
                    viewMode = props.viewMode,
                    defaultWalkTime = props.defaultWalkTime,
                    hasTransfer = props.hasTransfer,
                    indirectRedListPax = redListPaxInfo,
                    directRedListFlight = directRedListFlight,
                    airportConfig = props.airportConfig,
                    redListUpdates = props.redListUpdates,
                    includeIndirectRedListColumn = redListPaxExist,
                  ))
              }.toTagMod)
          ),
          excludedPaxNote
        )
      }
      else <.div("No flights to display")
    }
    .componentDidMount(_ => StickyTableHeader("[data-sticky]"))
    .build

  def tableHead(props: Props, timelineTh: TagMod, queues: Seq[Queue], redListPaxExist: Boolean, shortLabel: Boolean): TagOf[TableSection] = {
    val redListHeading = "Red List Pax"
    val estChoxHeading = "Est Chox"
    val isMobile = dom.window.innerWidth < 800
    val columns = List(
      ("Flight", Option("arrivals__table__flight-code")),
      (if (isMobile) "Ori" else "Origin", None),
      ("Country", Option("country")),
      (redListHeading, None),
      (if (isMobile || shortLabel) "Gt/St" else "Gate / Stand", Option("gate-stand")),
      ("Status", Option("status")),
      (if (isMobile || shortLabel) "Sch" else "Scheduled", None),
      (if (isMobile || shortLabel) "Exp" else "Expected", None),
      ("Exp PCP", Option("arrivals__table__flight-est-pcp")),
      ("Est PCP Pax", Option("arrivals__table__flight__pcp-pax__header")))

    val portColumnThs = columns
      .filter {
        case (label, _) => label != estChoxHeading || props.hasEstChox
      }
      .filter {
        case (label, _) => label != redListHeading || (props.displayRedListInfo && redListPaxExist)
      }
      .map {
        case (label, Some(className)) if label == "Est PCP Pax" => <.th(
          <.div(^.cls := className, label, " ", totalPaxTooltip)
        )
        case (label, None) if label == "Expected" || label == "Exp" => <.th(
          <.div(label, " ", expTimeTooltip)
        )
        case (label, None) if label == "Flight" => <.th(
          <.div(^.cls := "arrivals__table__flight-code-wrapper", label, " ", wbrFlightColorTooltip)
        )
        case (label, None) => <.th(label)
        case (label, Some(className)) if className == "status" => <.th(label, " ", arrivalStatusTooltip, ^.className := className)
        case (label, Some(className)) if className == "gate-stand" => <.th(label, " ", gateOrStandTh, ^.className := className)
        case (label, Some(className)) if className == "country" => <.th(label, " ", countryTooltip, ^.className := className)
        case (label, Some(className)) => <.th(label, ^.className := className)
      }
      .toTagMod

    val queueDisplayNames = queues.map { q =>
      val queueName: String = Queues.displayName(q)
      <.th(queueName, " ", splitsTableTooltip)
    }.toTagMod

    val transferPaxTh = <.th("Transfer Pax")

    <.thead(
      ^.className := "sticky-top",
      if (props.hasTransfer) {
        <.tr(
          timelineTh,
          portColumnThs,
          queueDisplayNames,
          transferPaxTh
        )
      } else {
        <.tr(
          timelineTh,
          portColumnThs,
          queueDisplayNames
        )
      }
    )
  }

  private def gateOrStandTh = {
    <.span(
      Tippy.info(
        "Select any gate / stand below to see the walk time. If it's not correct, contact us and we'll change it for you."
      )
    )
  }
}

