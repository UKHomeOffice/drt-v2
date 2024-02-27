package drt.client.components


import diode.UseValueEq
import drt.client.components.FlightComponents.SplitsGraph
import drt.client.components.FlightTableRow.SplitsGraphComponentFn
import drt.client.components.ToolTips._
import drt.client.logger.LoggerFactory
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared._
import drt.shared.api.{FlightManifestSummary, WalkTimes}
import drt.shared.redlist.{LhrRedListDatesImpl, LhrTerminalTypes}
import io.kinoplan.scalajs.react.material.ui.core.MuiTypography
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{CtorType, _}
import org.scalajs.dom
import org.scalajs.dom.html.{Span, TableCell, TableSection}
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, PortCode, Queues}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.immutable.HashSet

object FlightTableContent {
  private val log = LoggerFactory.getLogger(getClass.getName)

  case class Props(portState: PortState,
                   flightManifestSummaries: Map[ArrivalKey, FlightManifestSummary],
                   queueOrder: Seq[Queue],
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
                   flaggedNationalities: Set[Country],
                   viewStart: SDateLike,
                   viewEnd: SDateLike,
                   paxFeedSourceOrder: List[FeedSource],
                   filterFlightNumber: String,
                  ) extends UseValueEq

  implicit val reuseProps: Reusability[Props] = Reusability {
    (a, b) =>
      a.portState.latestUpdate == b.portState.latestUpdate &&
        a.flightManifestSummaries == b.flightManifestSummaries &&
        a.flaggedNationalities == b.flaggedNationalities &&
        a.viewStart == b.viewStart &&
        a.viewEnd == b.viewEnd
  }

  def apply(shortLabel: Boolean = false,
            originMapper: PortCode => VdomNode = portCode => portCode.toString,
            splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div()
           ): Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ArrivalsTableContent")
    .render_PS { (props, _) =>
      val flightDisplayFilter = props.airportConfig.portCode match {
        case PortCode("LHR") => LhrFlightDisplayFilter(props.redListUpdates, (portCode, _, _) =>
          props.redListPorts.contains(portCode), LhrTerminalTypes(LhrRedListDatesImpl))
        case _ => DefaultFlightDisplayFilter
      }

      val flights: Seq[ApiFlightWithSplits] = props.portState.window(props.viewStart, props.viewEnd, props.paxFeedSourceOrder)
        .flights.values.toList.filter(f => f.apiFlight.flightCodeString.contains(props.filterFlightNumber))

      flights
        .groupBy(f =>
          (f.apiFlight.Scheduled, f.apiFlight.Terminal, f.apiFlight.Origin)
        )
        .foreach { case (_, flights) =>
          if (flights.size > 1) {
            flights.foreach { f =>
              val flightCode = f.apiFlight.flightCodeString
              val paxSources = f.apiFlight.PassengerSources.map(ps => s"${ps._1.name}: ${ps._2.actual}").mkString(", ")
              val splitSources = f.splits.map(s => s"${s.source.toString}: ${s.totalPax}").mkString(", ")
              log.info(s"Codeshare flight ${SDate(f.apiFlight.Scheduled).prettyDateTime} $flightCode :: $paxSources :: $splitSources")
            }
          }
        }
      val flightsForTerminal =
        flightDisplayFilter.forTerminalIncludingIncomingDiversions(flights, props.terminal)
      val flightsWithCodeShares = CodeShares.uniqueArrivalsWithCodeShares(props.paxFeedSourceOrder)(flightsForTerminal.toSeq)
      val sortedFlights = flightsWithCodeShares.sortBy(_._1.apiFlight.PcpTime.getOrElse(0L))

      if (sortedFlights.nonEmpty) {
        val redListPaxExist = sortedFlights.exists(_._1.apiFlight.RedListPax.exists(_ > 0))
        <.div(
          <.div(if (props.filterFlightNumber.nonEmpty) MuiTypography()("Flights displayed : ", <.b(s"${sortedFlights.length}")) else EmptyVdom),
          <.table(
            ^.className := "arrivals-table table-striped",
            tableHead(props, props.queueOrder, redListPaxExist, shortLabel, props.flaggedNationalities.nonEmpty),
            <.tbody(
              sortedFlights.zipWithIndex.map {
                case ((flightWithSplits, codeShares), idx) =>
                  val isRedListOrigin = props.redListPorts.contains(flightWithSplits.apiFlight.Origin)
                  val directRedListFlight = redlist.DirectRedListFlight(props.viewMode.dayEnd.millisSinceEpoch,
                    props.portCode,
                    props.terminal,
                    flightWithSplits.apiFlight.Terminal,
                    isRedListOrigin)
                  val redListPaxInfo = redlist.IndirectRedListPax(props.displayRedListInfo, flightWithSplits)
                  FlightTableRow.component(FlightTableRow.Props(
                    flightWithSplits = flightWithSplits,
                    codeShareFlightCodes = codeShares,
                    idx = idx,
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
                    walkTimes = props.walkTimes,
                    flaggedNationalities = props.flaggedNationalities,
                    manifestSummary = props.flightManifestSummaries.get(ArrivalKey(flightWithSplits.apiFlight)),
                    paxFeedSourceOrder = props.paxFeedSourceOrder,
                  ))
              }.toTagMod)
          ),
        )
      }
      else <.div("No flights to display")
    }
    .configure(Reusability.shouldComponentUpdate)
    .build

  def tableHead(props: Props,
                queues: Seq[Queue],
                redListPaxExist: Boolean,
                shortLabel: Boolean,
                showFlagger: Boolean
               ): TagOf[TableSection] = {
    val redListHeading = "Red List Pax"
    val isMobile = dom.window.innerWidth < 800
    val columns = columnHeaders(shortLabel, redListHeading, isMobile, showFlagger)

    val portColumnThs = columnHeadersWithClasses(columns, props.hasEstChox, props.displayRedListInfo, redListPaxExist, redListHeading)
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
          portColumnThs,
          queueDisplayNames,
          transferPaxTh
        )
      } else {
        <.tr(
          portColumnThs,
          queueDisplayNames
        )
      }
    )
  }

  private def columnHeadersWithClasses(columns: Seq[(String, Option[String])],
                                       hasEstChox: Boolean,
                                       displayRedListInfo: Boolean,
                                       redListPaxExist: Boolean,
                                       redListHeading: String): Seq[VdomTagOf[TableCell]] = {
    val estChoxHeading = "Est Chox"
    columns
      .filter {
        case (label, _) => label != estChoxHeading || hasEstChox
      }
      .filter {
        case (label, _) => label != redListHeading || (displayRedListInfo && redListPaxExist)
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
  }

  private def columnHeaders(shortLabel: Boolean, redListHeading: String, isMobile: Boolean, showFlagger: Boolean): Seq[(String, Option[String])] =
    List(
      Option(("Flight", Option("arrivals__table__flight-code"))),
      Option((if (isMobile) "Ori" else "Origin", None)),
      Option(("Country", Option("country"))),
      Option((redListHeading, None)),
      if (showFlagger) Option(("Nationality ICAO code", Option("arrivals__table__flags-column"))) else None,
      Option((if (isMobile || shortLabel) "Gt/St" else "Gate / Stand", Option("gate-stand"))),
      Option(("Status", Option("status"))),
      Option((if (isMobile || shortLabel) "Sch" else "Scheduled", None)),
      Option((if (isMobile || shortLabel) "Exp" else "Expected", None)),
      Option(("Exp PCP", Option("arrivals__table__flight-est-pcp"))),
      Option(("Est PCP Pax", Option("arrivals__table__flight__pcp-pax__header"))),
    ).collect {
      case Some(column) => column
    }

  private def gateOrStandTh: VdomTagOf[Span] =
    <.span(
      Tippy.info(
        "Select any gate / stand below to see the walk time. If it's not correct, contact us and we'll change it for you."
      )
    )
}
