package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import diode.react.ModelProxy
import drt.client.actions.Actions.{GetArrivalSources, GetArrivalSourcesForPointInTime, RemoveArrivalSources}
import drt.client.components.FlightComponents.SplitsGraph
import drt.client.components.FlightTableRow.SplitsGraphComponentFn
import drt.client.components.ToolTips._
import drt.client.components.styles.ArrivalsPageStylesDefault
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import drt.shared.TimeUtil.millisToMinutes
import drt.shared._
import drt.shared.api.{Arrival, PassengerInfoSummary}
import drt.shared.dates.UtcDate
import drt.shared.redlist._
import drt.shared.splits.ApiSplitsToSplitRatio
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.vdom.{TagMod, TagOf, html_<^}
import japgolly.scalajs.react.{CtorType, _}
import org.scalajs.dom.html.{Div, Span, TableSection}
import scalacss.ScalaCssReact
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.ArrivalSource
import uk.gov.homeoffice.drt.ports.{AirportConfig, PortCode, Queues}
import uk.gov.homeoffice.drt.redlist.RedListUpdates

import scala.collection.immutable.{HashSet, Map}

object FlightsWithSplitsTable {
  case class Props(flightsWithSplits: List[ApiFlightWithSplits],
                   passengerInfoSummaryByDay: Map[UtcDate, Map[ArrivalKey, PassengerInfoSummary]],
                   queueOrder: Seq[Queue], hasEstChox: Boolean,
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

  def ArrivalsTable(timelineComponent: Option[Arrival => VdomNode] = None,
                    originMapper: PortCode => VdomNode = portCode => portCode.toString,
                    splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div()
                   ): Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props](displayName = "ArrivalsTable")
    .render_P(props => {

      val flightsWithSplits = props.flightsWithSplits
      val flightsWithCodeShares: Seq[(ApiFlightWithSplits, Set[Arrival])] = FlightTableComponents.uniqueArrivalsWithCodeShares(flightsWithSplits)
      val sortedFlights = flightsWithCodeShares.sortBy(_._1.apiFlight.PcpTime)
      val isTimeLineSupplied = timelineComponent.isDefined
      val timelineTh = (if (isTimeLineSupplied) <.th("Timeline") :: Nil else List[TagMod]()).toTagMod

      if (sortedFlights.nonEmpty) {
        val dataStickyAttr = VdomAttr("data-sticky") := "data-sticky"
        val classesAttr = ^.className := "table table-responsive table-striped table-hover table-sm"
        val excludedPaxNote = if (props.redListOriginWorkloadExcluded)
          "* Passengers from CTA & Red List origins do not contribute to PCP workload"
        else
          "* Passengers from CTA origins do not contribute to PCP workload"

        <.div(
          (props.loggedInUser.hasRole(ArrivalSource), props.arrivalSources) match {
            case (true, Some((_, sourcesPot))) =>
              <.div(^.tabIndex := 0,
                <.div(^.className := "popover-overlay", ^.onClick --> Callback(SPACircuit.dispatch(RemoveArrivalSources))),
                <.div(^.className := "dashboard-arrivals-popup", ArrivalInfo.SourcesTable(ArrivalInfo.Props(sourcesPot)))
              )
            case _ => <.div()
          },

          <.div(^.id := "toStick", ^.className := "container sticky",
            <.table(
              ^.id := "sticky",
              classesAttr,
              tableHead(props, timelineTh, props.queueOrder)))
          ,
          <.table(
            ^.id := "sticky-body",
            dataStickyAttr,
            classesAttr,
            tableHead(props, timelineTh, props.queueOrder),
            <.tbody(
              sortedFlights.zipWithIndex.map {
                case ((flightWithSplits, codeShares), idx) =>
                  val maybePassengerInfo: Option[PassengerInfoSummary] = props
                    .passengerInfoSummaryByDay
                    .get(SDate(flightWithSplits.apiFlight.Scheduled).toUtcDate)
                    .flatMap(_.get(ArrivalKey(flightWithSplits.apiFlight)))
                  val isRedListOrigin = props.redListPorts.contains(flightWithSplits.apiFlight.Origin)
                  val directRedListFlight = redlist.DirectRedListFlight(props.viewMode.dayEnd.millisSinceEpoch, props.portCode, props.terminal, flightWithSplits.apiFlight.Terminal, isRedListOrigin)
                  val redListPaxInfo = redlist.IndirectRedListPax(props.displayRedListInfo, flightWithSplits)
                  FlightTableRow.component(FlightTableRow.Props(
                    flightWithSplits = flightWithSplits,
                    maybePassengerInfoSummary = maybePassengerInfo,
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
                  ))
              }.toTagMod)
          ),
          excludedPaxNote
        )
      }
      else
        <.div("No flights to display")
    })
    .componentDidMount(_ => StickyTableHeader("[data-sticky]"))
    .build

  def tableHead(props: Props, timelineTh: TagMod, queues: Seq[Queue]): TagOf[TableSection] = {
    val redListHeading = "Red List Pax"
    val estChoxHeading = "Est Chox"
    
    val columns = List(
      ("Flight", Option("arrivals__table__flight-code")),
      ("Origin", None),
      ("Country", Option("country")),
      (redListHeading, None),
      ("Gate / Stand", Option("gate-stand")),
      ("Status", Option("status")),
      ("Sch", None),
      ("Est", None),
      ("Act", None),
      (estChoxHeading, None),
      ("Act Chox", None),
      ("Est PCP", Option("arrivals__table__flight-est-pcp")),
      ("Est PCP Pax", Option("arrivals__table__flight__pcp-pax__header")))

    val portColumnThs = columns
      .filter {
        case (label, _) => label != estChoxHeading || props.hasEstChox
      }
      .filter {
        case (label, _) => label != redListHeading || props.displayRedListInfo
      }
      .map {
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

object FlightTableRow {

  import FlightTableComponents._

  type OriginMapperF = PortCode => VdomNode

  type SplitsGraphComponentFn = SplitsGraph.Props => TagOf[Div]

  case class Props(flightWithSplits: ApiFlightWithSplits,
                   maybePassengerInfoSummary: Option[PassengerInfoSummary],
                   codeShares: Set[Arrival],
                   idx: Int,
                   timelineComponent: Option[Arrival => html_<^.VdomNode],
                   originMapper: OriginMapperF = portCode => portCode.toString,
                   splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div(),
                   splitsQueueOrder: Seq[Queue],
                   hasEstChox: Boolean,
                   loggedInUser: LoggedInUser,
                   viewMode: ViewMode,
                   defaultWalkTime: Long,
                   hasTransfer: Boolean,
                   indirectRedListPax: IndirectRedListPax,
                   directRedListFlight: DirectRedListFlight,
                   airportConfig: AirportConfig,
                   redListUpdates: RedListUpdates,
                  ) extends UseValueEq

  case class RowState(hasChanged: Boolean)

  val component: Component[Props, RowState, Unit, CtorType.Props] = ScalaComponent.builder[Props](displayName = "TableRow")
    .initialState[RowState](RowState(false))
    .render_PS((props, state) => {
      val codeShares = props.codeShares
      val flightWithSplits = props.flightWithSplits
      val flight = flightWithSplits.apiFlight
      val allCodes = flight.flightCodeString :: codeShares.map(_.flightCodeString).toList

      val hasChangedStyle = if (state.hasChanged) ^.background := "rgba(255, 200, 200, 0.5) " else ^.outline := ""
      val timeIndicatorClass = if (flight.PcpTime.getOrElse(0L) < SDate.now().millisSinceEpoch) "before-now" else "from-now"

      val queuePax: Map[Queue, Int] = ApiSplitsToSplitRatio
        .paxPerQueueUsingBestSplitsAsRatio(flightWithSplits).getOrElse(Map())

      val flightCodeClass = if (props.loggedInUser.hasRole(ArrivalSource))
        "arrivals__table__flight-code arrivals__table__flight-code--clickable"
      else
        "arrivals__table__flight-code"

      def flightCodeElement(flightCodes: String, outgoingDiversion: Boolean, incomingDiversion: Boolean): VdomTagOf[Span] =
        if (props.loggedInUser.hasRole(ArrivalSource)) {
          val diversionClass = (outgoingDiversion, incomingDiversion) match {
            case (_, true) => "arrivals__table__flight-code-incoming-diversion"
            case (true, _) => "arrivals__table__flight-code-outgoing-diversion"
            case _ => ""
          }
          <.span(
            ^.cls := s"arrivals__table__flight-code-value $diversionClass",
            ^.onClick --> Callback(SPACircuit.dispatch {
              props.viewMode match {
                case vm: ViewDay if vm.isHistoric(SDate.now()) =>
                  GetArrivalSourcesForPointInTime(props.viewMode.time.addHours(28), props.flightWithSplits.unique)
                case _: ViewPointInTime =>
                  GetArrivalSourcesForPointInTime(props.viewMode.time, props.flightWithSplits.unique)
                case _ =>
                  GetArrivalSources(props.flightWithSplits.unique)
              }
            }),
            flightCodes,
          )
        } else
          <.span(
            ^.cls := "arrivals__table__flight-code-value",
            flightCodes
          )

      val outgoingDiversion = props.directRedListFlight.outgoingDiversion
      val ctaOrRedListMarker = if (flight.Origin.isDomesticOrCta) "*" else ""
      val flightCodes = s"${allCodes.mkString(" - ")}$ctaOrRedListMarker"

      val firstCells = List[TagMod](
        <.td(^.className := flightCodeClass,
          <.div(
            ^.cls := "arrivals__table__flight-code-wrapper",
            flightCodeElement(flightCodes, props.directRedListFlight.outgoingDiversion, props.directRedListFlight.incomingDiversion),
            if (flightWithSplits.hasValidApi)
              props
                .maybePassengerInfoSummary
                .map { info =>
                  <.div(^.className := "arrivals__table__flight-code__info",
                    FlightChartComponent(FlightChartComponent.Props(flightWithSplits, info)))
                }
            else EmptyVdom
          )),
        <.td(props.originMapper(flight.Origin)),
        <.td(TerminalContentComponent.airportWrapper(flight.Origin) { proxy: ModelProxy[Pot[AirportInfo]] =>
          <.span(
            proxy().renderEmpty(<.span()),
            proxy().render(ai => {
              val style = if (props.indirectRedListPax.isEnabled && isRedListCountry(ai.country, props.viewMode.dayEnd, props.redListUpdates)) {
                ScalaCssReact.scalacssStyleaToTagMod(ArrivalsPageStylesDefault.redListCountryField)
              } else EmptyVdom

              <.span(
                style,
                ai.country
              )
            })
          )
        }),
        props.indirectRedListPax match {
          case NoIndirectRedListPax => EmptyVdom
          case NeboIndirectRedListPax(Some(pax)) => <.td(<.span(^.className := "badge", pax))
          case NeboIndirectRedListPax(None) => <.td(EmptyVdom)
        },
        <.td(gateOrStand(flight, props.airportConfig, props.directRedListFlight.paxDiversion)),
        <.td(flight.displayStatus.description),
        <.td(localDateTimeWithPopup(Option(flight.Scheduled))),
        <.td(localDateTimeWithPopup(flight.Estimated)),
        <.td(localDateTimeWithPopup(flight.Actual)),
      )
      val estCell = List(<.td(localDateTimeWithPopup(flight.EstimatedChox)))
      val lastCells = List[TagMod](
        <.td(localDateTimeWithPopup(flight.ActualChox)),
        <.td(pcpTimeRange(flightWithSplits), ^.className := "arrivals__table__flight-est-pcp"),
        <.td(FlightComponents.paxComp(flightWithSplits, props.directRedListFlight, flight.Origin.isDomesticOrCta))
      )
      val flightFields = if (props.hasEstChox) firstCells ++ estCell ++ lastCells else firstCells ++ lastCells

      val paxClass = FlightComponents.paxClassFromSplits(flightWithSplits)

      val flightId = flight.uniqueId.toString

      val timeLineTagMod = props.timelineComponent.map(timeline => <.td(timeline(flight))).toList.toTagMod

      val cancelledClass = if (flight.isCancelled) " arrival-cancelled" else ""
      val noPcpPax = if (flight.Origin.isCta || outgoingDiversion) " arrival-cta" else ""
      val trClassName = s"${offScheduleClass(flight, props.airportConfig.timeToChoxMillis)} $timeIndicatorClass$cancelledClass$noPcpPax"

      val queueTagMod = props.splitsQueueOrder.map { q =>
        val pax = if (!flight.Origin.isDomesticOrCta) queuePax.getOrElse(q, 0).toString else "-"
        <.td(^.className := s"queue-split $paxClass right",
          <.span(pax, ^.className := s"${q.toString.toLowerCase()}-queue-pax"))
      }.toTagMod

      if (props.hasTransfer) {
        <.tr(
          ^.key := flightId,
          ^.className := trClassName,
          hasChangedStyle,
          timeLineTagMod,
          flightFields.toTagMod,
          queueTagMod,
          <.td(FlightComponents.paxTransferComponent(flight))
        )
      } else {
        <.tr(
          ^.key := flightId,
          ^.className := trClassName,
          hasChangedStyle,
          timeLineTagMod,
          flightFields.toTagMod,
          queueTagMod
        )
      }

    })
    .build

  private def gateOrStand(arrival: Arrival, airportConfig: AirportConfig, paxAreDiverted: Boolean): VdomTagOf[Span] = {
    val gateOrStand = <.span(s"${arrival.Gate.getOrElse("")} / ${arrival.Stand.getOrElse("")}")
    arrival.walkTime(airportConfig.timeToChoxMillis, airportConfig.firstPaxOffMillis).map { wt =>
      val description = (paxAreDiverted, arrival.Stand.isDefined, arrival.Gate.isDefined) match {
        case (true, _, _) => "walk time including transfer bus"
        case (false, true, _) => "walk time from stand"
        case (false, false, true) => "walk time from gate"
        case _ => "default walk time"
      }
      val walkTimeString = MinuteAsAdjective(millisToMinutes(wt)).display + " " + description
      <.span(Tippy.interactive(<.span(walkTimeString), gateOrStand))
    }.getOrElse(gateOrStand)
  }

  def offScheduleClass(arrival: Arrival, timeToChoxMillis: Long): String = {
    val eta = arrival.bestArrivalTime(timeToChoxMillis)
    val differenceFromScheduled = eta - arrival.Scheduled
    val hourInMillis = 3600000
    val offScheduleClass = if (differenceFromScheduled > hourInMillis || differenceFromScheduled < -1 * hourInMillis)
      "danger"
    else ""
    offScheduleClass
  }

  def isRedListCountry(country: String, date: SDateLike, redListUpdates: RedListUpdates): Boolean =
    redListUpdates.countryCodesByName(date.millisSinceEpoch).keys.exists(_.toLowerCase == country.toLowerCase)

  def apply(props: Props): Unmounted[Props, RowState, Unit] = component(props)

}
