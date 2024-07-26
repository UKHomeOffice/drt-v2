package drt.client.components


import diode.UseValueEq
import diode.data.Pot
import drt.client.components.FlightComponents.SplitsGraph
import drt.client.components.FlightTableRow.SplitsGraphComponentFn
import drt.client.components.ToolTips._
import drt.client.logger.LoggerFactory
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared._
import drt.shared.api.{FlightManifestSummary, PaxAgeRange, WalkTimes}
import drt.shared.redlist.{LhrRedListDatesImpl, LhrTerminalTypes}
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import io.kinoplan.scalajs.react.material.ui.core.{MuiAlert, MuiTypography}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.{TagOf, html_<^}
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.{CtorType, _}
import org.scalajs.dom
import org.scalajs.dom.html.{TableCell, TableSection}
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.ports.PaxTypes.VisaNational
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, PortCode, Queues}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.immutable.HashSet
import scala.scalajs.js

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
                   flaggedAgeGroups: Set[PaxAgeRange],
                   showNumberOfVisaNationals: Boolean,
                   showHighlightedRows: Boolean,
                   showRequireAllSelected: Boolean,
                   viewStart: SDateLike,
                   viewEnd: SDateLike,
                   paxFeedSourceOrder: List[FeedSource],
                   filterFlightSearch: String,
                   showFlagger: Boolean,
                  ) extends UseValueEq

  implicit val reuseProps: Reusability[Props] = Reusability {
    (a, b) =>
      a.portState.latestUpdate == b.portState.latestUpdate &&
        a.flightManifestSummaries == b.flightManifestSummaries &&
        a.flaggedNationalities == b.flaggedNationalities &&
        a.flaggedAgeGroups == b.flaggedAgeGroups &&
        a.filterFlightSearch == b.filterFlightSearch &&
        a.showNumberOfVisaNationals == b.showNumberOfVisaNationals &&
        a.showHighlightedRows == b.showHighlightedRows &&
        a.showRequireAllSelected == b.showRequireAllSelected &&
        a.viewStart == b.viewStart &&
        a.viewEnd == b.viewEnd
  }

  case class Model(airportInfos: Map[PortCode, Pot[AirportInfo]]) extends UseValueEq

  def apply(shortLabel: Boolean = false,
            originMapper: (PortCode, html_<^.TagMod) => VdomNode,// = portCode => portCode.toString,
            splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div()
           ): Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ArrivalsTableContent")
    .render_PS { (props, _) =>
      val modelRCP = SPACircuit.connect(m => Model(airportInfos = m.airportInfos))

      val flightDisplayFilter = props.airportConfig.portCode match {
        case PortCode("LHR") => LhrFlightDisplayFilter(props.redListUpdates, (portCode, _, _) =>
          props.redListPorts.contains(portCode), LhrTerminalTypes(LhrRedListDatesImpl))
        case _ => DefaultFlightDisplayFilter
      }

      def filterFlights(flights: List[ApiFlightWithSplits],
                        filter: String,
                        airportInfos: Map[PortCode, Pot[AirportInfo]]): List[ApiFlightWithSplits] = {
        flights.filter(f => f.apiFlight.flightCodeString.toLowerCase.contains(filter.toLowerCase)
          || f.apiFlight.Origin.iata.toLowerCase.contains(filter.toLowerCase)
          || airportInfos.get(f.apiFlight.Origin).exists(airportInfo => airportInfo
          .exists(_.country.toLowerCase.contains(filter.toLowerCase))))
      }

      modelRCP(modelMP => {
        val model: Model = modelMP()

        val flightsForWindow = props.portState.window(props.viewStart, props.viewEnd, props.paxFeedSourceOrder)
        val flights: Seq[ApiFlightWithSplits] = filterFlights(flightsForWindow.flights.values.toList, props.filterFlightSearch, model.airportInfos)

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
        val flightsWithCodeShares = CodeShares.uniqueFlightsWithCodeShares(props.paxFeedSourceOrder)(flightsForTerminal.toSeq)
        val sortedFlights = flightsWithCodeShares.sortBy(_._1.apiFlight.PcpTime.getOrElse(0L))
        val showFlagger = props.flaggedNationalities.nonEmpty ||
          props.flaggedAgeGroups.nonEmpty ||
          props.showNumberOfVisaNationals

        <.div(
          if (sortedFlights.nonEmpty) {
            val redListPaxExist = sortedFlights.exists(_._1.apiFlight.RedListPax.exists(_ > 0))
            <.div(
              if (props.filterFlightSearch.nonEmpty) {
                <.div(MuiTypography(sx = SxProps(Map("padding" -> "16px 0 16px 0")))("Flights displayed : ", <.b(s"${sortedFlights.length}")))
              } else <.div(),
              <.div(<.table(
                ^.className := "arrivals-table table-striped",
                tableHead(props, props.queueOrder, redListPaxExist, shortLabel, showFlagger),
                <.tbody(
                  sortedFlights.zipWithIndex.flatMap {
                    case ((flightWithSplits, codeShares), idx) =>
                      val isRedListOrigin = props.redListPorts.contains(flightWithSplits.apiFlight.Origin)
                      val directRedListFlight = redlist.DirectRedListFlight(props.viewMode.dayEnd.millisSinceEpoch,
                        props.portCode,
                        props.terminal,
                        flightWithSplits.apiFlight.Terminal,
                        isRedListOrigin)
                      val manifestSummary: Option[FlightManifestSummary] = props.flightManifestSummaries.get(ArrivalKey(flightWithSplits.apiFlight))

                      val redListPaxInfo = redlist.IndirectRedListPax(props.displayRedListInfo, flightWithSplits)

                      def flightTableRow(showHightlighted: Boolean) = FlightTableRow.Props(
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
                        flaggedAgeGroups = props.flaggedAgeGroups,
                        showNumberOfVisaNationals = props.showNumberOfVisaNationals,
                        showHighlightedRows = props.showNumberOfVisaNationals,
                        showRequireAllSelected = props.showRequireAllSelected,
                        manifestSummary = props.flightManifestSummaries.get(ArrivalKey(flightWithSplits.apiFlight)),
                        paxFeedSourceOrder = props.paxFeedSourceOrder,
                        showHightLighted = showHightlighted)

                      val flaggedNationalitiesInSummary: Set[Option[Int]] = props.flaggedNationalities.map { country =>
                        manifestSummary.map(_.nationalities.find(n => n._1.code == country.threeLetterCode).map(_._2).getOrElse(0))
                      }
                      val flaggedAgeGroupInSummary: Set[Option[Int]] = props.flaggedAgeGroups.map { ageRanges =>
                        manifestSummary.map(_.ageRanges.find(n => n._1 == ageRanges).map(_._2).getOrElse(0))
                      }
                      val visaNationalsInSummary: Option[Int] = manifestSummary.map(_.paxTypes.getOrElse(VisaNational, 0))

                      val conditionsAndFlaggedSummary: Seq[(Boolean, Set[Option[Int]])] = List(
                        (props.flaggedNationalities.nonEmpty, flaggedNationalitiesInSummary),
                        (props.flaggedAgeGroups.nonEmpty, flaggedAgeGroupInSummary),
                        (props.showNumberOfVisaNationals, Set(visaNationalsInSummary))
                      )

                      val trueConditionsAndChips: Seq[(Boolean, Set[Option[Int]])] = conditionsAndFlaggedSummary.filter(_._1)

                      val isFlaggedInSummaryExists = flaggedNationalitiesInSummary.flatten.sum > 0 && props.flaggedNationalities.nonEmpty ||
                        flaggedAgeGroupInSummary.flatten.sum > 0 && props.flaggedAgeGroups.nonEmpty ||
                        visaNationalsInSummary.getOrElse(0) > 0 && props.showNumberOfVisaNationals

                      (props.showHighlightedRows, props.showRequireAllSelected) match {
                        case (true, true) =>
                          if (trueConditionsAndChips.map(_._2).forall(_.exists(a => a.sum > 0)))
                            Some(FlightTableRow.component(flightTableRow(true)))
                          else None
                        case (true, false) => if (isFlaggedInSummaryExists) {
                          Some(FlightTableRow.component(flightTableRow(true)))
                        } else None
                        case (_, _) =>
                          Some(FlightTableRow.component(flightTableRow(isFlaggedInSummaryExists)))
                      }
                  }.toTagMod
                ))))
          }
          else <.div(^.style := js.Dictionary("paddingTop" -> "16px", "paddingBottom" -> "16px"),
            if (flightsForWindow.flights.isEmpty) {
              <.div(^.style := js.Dictionary("border" -> "1px solid #014361"),
                MuiAlert(variant = MuiAlert.Variant.standard, color = "info", severity = "info")
                (MuiTypography(sx = SxProps(Map("fontWeight" -> "bold")))("No flights to display.")))
            } else {
              <.div(^.style := js.Dictionary("border" -> "1px solid #99001E"),
                MuiAlert(variant = MuiAlert.Variant.standard, color = "error", severity = "error")(
                  MuiTypography(sx = SxProps(Map("fontWeight" -> "bold")))("No flights found."), "Check the flight number or time period."))
            }
          )
        )
      })
    }.configure(Reusability.shouldComponentUpdate)
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

    val portColumnThs = columnHeadersWithClasses(columns, props.hasEstChox, props.displayRedListInfo, redListPaxExist, redListHeading).toTagMod

    val queueDisplayNames = <.th(
      <.span(^.className := "flex-uniform-size",
        queues.map(q => <.div(Queues.displayName(q), " ", splitsTableTooltip, ^.className := "arrivals_table__splits__queue-pax flex-horizontally")).toTagMod
      ),
      ^.className := "arrivals__table__flight-splits",
    )

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
          <.div(^.cls := className, label)
        )
        case (label, None) if label == "Expected" || label == "Exp" => <.th(
          <.div(^.className := "flex-horizonally", label, " ", expTimeTooltip)
        )
        case (label, None) if label == "Flight" => <.th(
          <.div(^.cls := "arrivals__table__flight-code-wrapper", label, " ", wbrFlightColorTooltip)
        )
        case (label, None) => <.th(label)
        case (label, Some(className)) if className == "country" => <.th(label, " ", countryTooltip, ^.className := className)
        case (label, Some(className)) => <.th(label, ^.className := className)
      }
  }

  private def columnHeaders(shortLabel: Boolean, redListHeading: String, isMobile: Boolean, showFlagger: Boolean): Seq[(String, Option[String])] =
    List(
      Option(("Flight", if (showFlagger) Option("arrivals__table__flight-code-with-highlight") else Option("arrivals__table__flight-code"))),
      if (showFlagger) Option(("Pax Info", Option("arrivals__table__flags-column"))) else None,
      Option((if (isMobile) "Ori" else "Origin, Country", Option("arrivals__table__flight-origin"))),
      Option((redListHeading, None)),
      Option((if (isMobile || shortLabel) "Gt/St" else "Gate / Stand", Option("gate-stand"))),
      Option(("Status", Option("status"))),
      Option((if (isMobile || shortLabel) "Sch" else "Scheduled", None)),
      Option((if (isMobile || shortLabel) "Exp" else "Expected", None)),
      Option(("Exp PCP", Option("arrivals__table__flight-est-pcp"))),
      Option(("Est PCP Pax", Option("arrivals__table__flight__pcp-pax__header"))),
    ).collect {
      case Some(column) => column
    }
}
