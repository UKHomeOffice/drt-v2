package drt.client.components


import diode.UseValueEq
import diode.data.Pot
import drt.client.SPAMain.TerminalPageTabLoc
import drt.client.components.DaySelectorComponent.searchForm
import drt.client.components.ToolTips._
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.client.services.handlers.UpdateUserPreferences
import drt.shared.api.{FlightManifestSummary, PaxAgeRange, WalkTimes}
import drt.shared.redlist.{DirectRedListFlight, IndirectRedListPax, LhrRedListDatesImpl, LhrTerminalTypes}
import drt.shared._
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import io.kinoplan.scalajs.react.material.ui.core.{MuiAlert, MuiFormControl, MuiSwitch, MuiTypography}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{TagOf, html_<^}
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, ScalaComponent}
import org.scalajs.dom
import org.scalajs.dom.html.{TableCell, TableSection}
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.immutable.{HashSet, Seq}
import scala.scalajs.js

object FlightTableContent {
  case class Props(flights: Pot[Seq[ApiFlightWithSplits]],
                   flightManifestSummaries: Map[ManifestKey, FlightManifestSummary],
                   queueOrder: Seq[Queue],
                   hasEstChox: Boolean,
                   loggedInUser: LoggedInUser,
                   viewMode: ViewMode,
                   hasTransfer: Boolean,
                   displayRedListInfo: Boolean,
                   terminal: Terminal,
                   portCode: PortCode,
                   redListPorts: HashSet[PortCode],
                   redListUpdates: RedListUpdates,
                   airportConfig: AirportConfig,
                   walkTimes: WalkTimes,
                   flightHighlight: FlightHighlight,
                   paxFeedSourceOrder: List[FeedSource],
                   shortLabel: Boolean = false,
                   originMapper: (PortCode, Option[PortCode], html_<^.TagMod) => VdomNode,
                   userPreferences: UserPreferences,
                   terminalPageTab: TerminalPageTabLoc,
                  ) extends UseValueEq

  class Backend {

    private val handleTogglePaxSourceIcon = (e: ReactEventFromInput, userPreferences: UserPreferences) => Callback {
      e.preventDefault()
      SPACircuit.dispatch(UpdateUserPreferences(userPreferences.copy(hidePaxDataSourceDescription = !e.target.checked)))
    }

    private def displayArrivalSearchDate(selectedDate: SDateLike, terminalPageTab: TerminalPageTabLoc): String = {
      val searchFormForDate = searchForm(selectedDate, terminalPageTab)
      s"${searchFormForDate.displayText} (${searchFormForDate.fromTime} - ${searchFormForDate.toTime})"
    }


    def render(props: Props): VdomElement = {
      val flightDisplayFilter = props.airportConfig.portCode match {
        case PortCode("LHR") => LhrFlightDisplayFilter(props.redListUpdates, (portCode, _, _) =>
          props.redListPorts.contains(portCode), LhrTerminalTypes(LhrRedListDatesImpl))
        case _ => DefaultFlightDisplayFilter
      }
      
      val content = for {
        flights <- props.flights
      } yield {
        val flightsForTerminal = flightDisplayFilter.forTerminalIncludingIncomingDiversions(flights, props.terminal)
        val flightsWithCodeShares = CodeShares.uniqueFlightsWithCodeShares(props.paxFeedSourceOrder)(flightsForTerminal.toSeq)
        val sortedFlights = flightsWithCodeShares.sortBy(_._1.apiFlight.PcpTime.getOrElse(0L))
        val ageGroups = props.flightHighlight.selectedAgeGroups.map(PaxAgeRange.parse).toSet
        val showFlagger = props.flightHighlight.selectedNationalities.nonEmpty ||
          ageGroups.nonEmpty ||
          props.flightHighlight.showNumberOfVisaNationals

        val highlightedFlightsCount: Int = FlightHighlighter
          .findHighlightedFlightsCount(sortedFlights,
            props.flightManifestSummaries,
            props.flightHighlight.selectedNationalities,
            ageGroups,
            props.flightHighlight.showNumberOfVisaNationals,
            props.flightHighlight.showOnlyHighlightedRows,
            props.flightHighlight.showRequireAllSelected)

        <.div(
          if (sortedFlights.nonEmpty) {
            val redListPaxExist = sortedFlights.exists(_._1.apiFlight.RedListPax.exists(_ > 0))
            <.div(
              <.div(^.style := js.Dictionary("paddingTop" -> "10px"), MuiTypography(sx = SxProps(Map("fontSize" -> "18px", "fontWeight" -> "bold")))(s"Arrivals, ${displayArrivalSearchDate(SDate(props.terminalPageTab.viewMode.localDate), props.terminalPageTab)}")),
              <.div(^.style := js.Dictionary("display" -> "flex", "justifyContent" -> "space-between", "alignItems" -> "center"))(
                <.div {
                  val flaggerInUse = props.flightHighlight.selectedNationalities.nonEmpty || ageGroups.nonEmpty || props.flightHighlight.showNumberOfVisaNationals
                  val flightCounts = if (flaggerInUse && props.flightHighlight.showOnlyHighlightedRows)
                    <.span(s"$highlightedFlightsCount flight${pluraliseString(highlightedFlightsCount)} shown and highlighted")
                  else if (flaggerInUse)
                    <.span(s"${sortedFlights.length} flight${pluraliseString(sortedFlights.length)} shown", " | ", <.b(s"$highlightedFlightsCount flight${pluraliseString(highlightedFlightsCount)} highlighted"))
                  else
                    <.span(s"${sortedFlights.length} flight${pluraliseString(sortedFlights.length)} shown")

                  MuiTypography(sx = SxProps(Map("padding" -> "16px 0 16px 0")))(flightCounts)
                },
                <.div(^.style := js.Dictionary("display" -> "flex", "justifyContent" -> "space-between", "alignItems" -> "center"),
                  MuiTypography()("Show pax data descriptions"),
                  MuiFormControl()(
                    MuiSwitch(defaultChecked = !props.userPreferences.hidePaxDataSourceDescription)
                    (^.onChange ==> ((e: ReactEventFromInput) => handleTogglePaxSourceIcon(e, props.userPreferences)))
                  ),
                  MuiTypography(sx = SxProps(Map("paddingRight" -> "10px")))(if (!props.userPreferences.hidePaxDataSourceDescription) "On" else "Off"),
                )
              ),
              <.div(
                <.table(
                  ^.className := "arrivals-table table-striped",
                  tableHead(props, props.queueOrder, redListPaxExist, props.shortLabel, showFlagger, props.userPreferences.hidePaxDataSourceDescription),
                  <.tbody(
                    ^.id := "sticky-body",
                    sortedFlights.flatMap {
                      case (flightWithSplits, codeShares) =>
                        val isRedListOrigin = props.redListPorts.contains(flightWithSplits.apiFlight.Origin)
                        val directRedListFlight = DirectRedListFlight(props.viewMode.dayEnd.millisSinceEpoch,
                          props.portCode,
                          props.terminal,
                          flightWithSplits.apiFlight.Terminal,
                          isRedListOrigin)

                        val maybeManifestSummary = props.flightManifestSummaries.get(ManifestKey(flightWithSplits.apiFlight))
                        val redListPaxInfo = IndirectRedListPax(props.displayRedListInfo, flightWithSplits)

                        def flightTableRow(showHightlighted: Boolean) = FlightTableRow.Props(
                          flightWithSplits = flightWithSplits,
                          codeShareFlightCodes = codeShares,
                          originMapper = props.originMapper,
                          splitsQueueOrder = props.queueOrder,
                          loggedInUser = props.loggedInUser,
                          viewMode = props.viewMode,
                          hasTransfer = props.hasTransfer,
                          indirectRedListPax = redListPaxInfo,
                          directRedListFlight = directRedListFlight,
                          airportConfig = props.airportConfig,
                          redListUpdates = props.redListUpdates,
                          includeIndirectRedListColumn = redListPaxExist,
                          walkTimes = props.walkTimes,
                          flaggedNationalities = props.flightHighlight.selectedNationalities,
                          flaggedAgeGroups = ageGroups,
                          showNumberOfVisaNationals = props.flightHighlight.showNumberOfVisaNationals,
                          showHighlightedRows = props.flightHighlight.showNumberOfVisaNationals,
                          showRequireAllSelected = props.flightHighlight.showRequireAllSelected,
                          maybeManifestSummary = maybeManifestSummary,
                          paxFeedSourceOrder = props.paxFeedSourceOrder,
                          showHighLighted = showHightlighted,
                          hidePaxDataSourceDescription = props.userPreferences.hidePaxDataSourceDescription,
                        )

                        FlightHighlighter.highlightedFlight(maybeManifestSummary,
                            props.flightHighlight.selectedNationalities,
                            ageGroups,
                            props.flightHighlight.showNumberOfVisaNationals,
                            props.flightHighlight.showOnlyHighlightedRows,
                            props.flightHighlight.showRequireAllSelected)
                          .map(h => FlightTableRow.component(flightTableRow(h)))

                    }.toTagMod
                  )))
            )
          }
          else <.div(^.style := js.Dictionary("paddingTop" -> "16px", "paddingBottom" -> "16px"),
            if (flights.isEmpty) {
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
      }
      content.getOrElse(LoadingOverlay())
    }
  }

  private def pluraliseString(highlightedFlightsCount: Int) = {
    if (highlightedFlightsCount != 1) "s" else ""
  }

  val component: Component[Props, Unit, Backend, CtorType.Props] =
    ScalaComponent.builder[Props]("FlightTableContent")
      .renderBackend[Backend]
      .build

  def apply(props: Props): VdomElement = component(props)

  def tableHead(props: Props,
                queues: Seq[Queue],
                redListPaxExist: Boolean,
                shortLabel: Boolean,
                showFlagger: Boolean,
                hidePaxDataSourceDescription: Boolean
               ): TagOf[TableSection] = {
    val redListHeading = "Red List Pax"
    val isMobile = dom.window.innerWidth < 800
    val columns = columnHeaders(shortLabel, redListHeading, isMobile, showFlagger)

    val portColumnThs = columnHeadersWithClasses(columns, props.hasEstChox, props.displayRedListInfo, redListPaxExist, redListHeading).toTagMod

    val queueDisplayNames = <.th(
      <.span(^.className := "flex-uniform-size",
        (Seq(if (hidePaxDataSourceDescription) <.div("", "", ^.className := "icon-header-data-quality") else EmptyVdom) ++
          queues.map(q => <.div(Queues.displayName(q), " ", ^.className := "arrivals_table__splits__queue-pax flex-horizontally"))).toTagMod
      ),
      ^.className := "arrivals__table__flight-splits",
    )

    val transferPaxTh = <.th(^.className := "arrivals__table__flight_transfer-pax", "Transfer Pax")

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
    val estChoxHeading = "Est Chocks"
    columns
      .filter {
        case (label, _) => label != estChoxHeading || hasEstChox
      }
      .filter {
        case (label, _) => label != redListHeading || (displayRedListInfo && redListPaxExist)
      }
      .map {
        case (label, Some(className)) if label == "Est PCP Pax" => <.th(^.className := "arrivals__table__flight__pcp-pax__header_column",
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
