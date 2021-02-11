package drt.client.components

import diode.data.Pot
import diode.react.ModelProxy
import drt.client.actions.Actions.{GetArrivalSources, GetArrivalSourcesForPointInTime, RemoveArrivalSources}
import drt.client.components.FlightComponents.{SplitsGraph, hasApiSplits}
import drt.client.components.FlightTableRow.SplitsGraphComponentFn
import drt.client.components.TooltipComponent._
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.api.{Arrival, PassengerInfoSummary, WalkTimes}
import drt.shared.dates.UtcDate
import drt.shared.splits.ApiSplitsToSplitRatio
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.vdom.html_<^.{<, _}
import japgolly.scalajs.react.vdom.{TagMod, TagOf, html_<^}
import japgolly.scalajs.react.{CtorType, _}
import org.scalajs.dom.html.{Div, Span, TableSection}

import scala.collection.immutable.Map

object FlightsWithSplitsTable {

  type BestPaxForArrivalF = Arrival => Int

  case class Props(flightsWithSplits: List[ApiFlightWithSplits],
                   passengerInfoSummaryByDay: Map[UtcDate, Map[ArrivalKey, PassengerInfoSummary]],
                   queueOrder: Seq[Queue], hasEstChox: Boolean,
                   arrivalSources: Option[(UniqueArrival, Pot[List[Option[FeedSourceArrival]]])],
                   hasArrivalSourcesAccess: Boolean,
                   viewMode: ViewMode,
                   pcpPaxFn: Arrival => Int,
                   walkTimes: WalkTimes,
                   defaultWalkTime: Long,
                   hasTransfer: Boolean
                  )

  implicit val propsReuse: Reusability[Props] = Reusability.by((props: Props) => {
    (props.flightsWithSplits, props.arrivalSources, props.passengerInfoSummaryByDay).hashCode()
  })

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
        <.div(
          (props.hasArrivalSourcesAccess, props.arrivalSources) match {
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

                  FlightTableRow.component(FlightTableRow.Props(
                    flightWithSplits,
                    maybePassengerInfo,
                    codeShares,
                    idx,
                    timelineComponent = timelineComponent,
                    originMapper = originMapper,
                    pcpPaxFn = props.pcpPaxFn,
                    splitsGraphComponent = splitsGraphComponent,
                    splitsQueueOrder = props.queueOrder,
                    hasEstChox = props.hasEstChox,
                    props.hasArrivalSourcesAccess,
                    props.viewMode,
                    props.walkTimes,
                    props.defaultWalkTime,
                    props.hasTransfer
                  ))
              }.toTagMod)
          )
        )
      }
      else
        <.div("No flights to display")
    })
    .configure(Reusability.shouldComponentUpdate)
    .componentDidMount(_ => StickyTableHeader("[data-sticky]"))
    .build

  def tableHead(props: Props, timelineTh: TagMod, queues: Seq[Queue]): TagOf[TableSection] = {
    val columns = List(
      ("Flight", None),
      ("Origin", None),
      ("Country", Option("country")),
      ("Gate / Stand", Option("gate-stand")),
      ("Status", Option("status")),
      ("Sch", None),
      ("Est", None),
      ("Act", None),
      ("Est Chox", None),
      ("Act Chox", None),
      ("Est PCP", None),
      ("Est PCP Pax", None))

    val portColumnThs = columns
      .filter {
        case (label, _) => label != "Est Chox" || props.hasEstChox
      }
      .map {
        case (label, None) if label == "Flight" => <.th(
          <.div(^.cls := "arrivals__table__flight-code-wrapper", label, " ", wbrFlightColorTooltip)
        )
        case (label, None) => <.th(label)
        case (label, Some(className)) if className == "status" => <.th(label, " ", arrivalStatusTooltip, ^.className := className)
        case (label, Some(className)) if className == "gate-stand" => <.th(label, " ", gateOrStandTh(className), ^.className := className)
        case (label, Some(className)) => <.th(label, ^.className := className)
      }
      .toTagMod

    val queueDisplayNames = queues.map { q =>
      val queueName: String = Queues.queueDisplayNames(q)
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

  private def gateOrStandTh(className: String) = {
    <.span(
      TippyJSComponent(
        <.span("Hover over any gate / stand below to see the walk time. If it's not correct, contact us and we'll change it for you.").rawElement,
        false, <.span(Icon.infoCircle)
      )
    )
  }
}

object FlightTableRow {

  import FlightTableComponents._

  type OriginMapperF = PortCode => VdomNode
  type BestPaxForArrivalF = Arrival => Int

  type SplitsGraphComponentFn = SplitsGraph.Props => TagOf[Div]

  case class Props(flightWithSplits: ApiFlightWithSplits,
                   maybePassengerInfoSummary: Option[PassengerInfoSummary],
                   codeShares: Set[Arrival],
                   idx: Int,
                   timelineComponent: Option[Arrival => html_<^.VdomNode],
                   originMapper: OriginMapperF = portCode => portCode.toString,
                   pcpPaxFn: Arrival => Int,
                   splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div(),
                   splitsQueueOrder: Seq[Queue],
                   hasEstChox: Boolean,
                   hasArrivalSourcesAccess: Boolean,
                   viewMode: ViewMode,
                   walkTimes: WalkTimes,
                   defaultWalkTime: Long,
                   hasTransfer: Boolean
                  )

  case class RowState(hasChanged: Boolean)

  implicit val propsReuse: Reusability[Props] = Reusability.by(p => (p.flightWithSplits.hashCode, p.idx, p.maybePassengerInfoSummary.hashCode))
  implicit val stateReuse: Reusability[RowState] = Reusability.derive[RowState]

  def bestArrivalTime(f: Arrival): MillisSinceEpoch = {
    val best = (
      Option(SDate(f.Scheduled)),
      f.Estimated.map(SDate(_)),
      f.Actual.map(SDate(_))
    ) match {
      case (Some(sd), None, None) => sd
      case (_, Some(est), None) => est
      case (_, _, Some(act)) => act
      case _ => throw new Exception(s"Flight has no scheduled date: $f")
    }

    best.millisSinceEpoch
  }

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
        .paxPerQueueUsingBestSplitsAsRatio(flightWithSplits, props.pcpPaxFn).getOrElse(Map())

      val flightCodeClass = if (props.hasArrivalSourcesAccess) "arrivals__table__flight-code arrivals__table__flight-code--clickable" else "arrivals__table__flight-code"

      val flightCodeElement = if (props.hasArrivalSourcesAccess) <.span(
        ^.cls := "arrivals__table__flight-code-value",
        ^.onClick --> Callback(SPACircuit.dispatch {
          props.viewMode match {
            case vm: ViewDay if vm.isHistoric(SDate.now()) =>
              GetArrivalSourcesForPointInTime(props.viewMode.time.addHours(28), props.flightWithSplits.unique)
            case vm: ViewPointInTime =>
              GetArrivalSourcesForPointInTime(props.viewMode.time, props.flightWithSplits.unique)
            case _ =>
              GetArrivalSources(props.flightWithSplits.unique)
          }
        }),
        allCodes.mkString(" - "))
      else <.span(
        ^.cls := "arrivals__table__flight-code-value",
        allCodes.mkString(" - ")
      )

      val firstCells = List[TagMod](

        <.td(
          ^.className := flightCodeClass,
          <.div(
            ^.cls := "arrivals__table__flight-code-wrapper",
            flightCodeElement,
            if (hasApiSplits(flightWithSplits: ApiFlightWithSplits))
              props
                .maybePassengerInfoSummary
                .map(info => FlightChartComponent(FlightChartComponent.Props(flightWithSplits, info)))
            else EmptyVdom
          )
        ),

        <.td(props.originMapper(flight.Origin)),
        <.td(TerminalContentComponent.airportWrapper(flight.Origin) { proxy: ModelProxy[Pot[AirportInfo]] =>
          <.span(
            proxy().renderEmpty(<.span()),
            proxy().render(ai => <.span(ai.country))
          )
        }),
        <.td(gateOrStand(props.walkTimes, props.defaultWalkTime, flight)),
        <.td(flight.Status.description),
        <.td(localDateTimeWithPopup(Option(flight.Scheduled))),
        <.td(localDateTimeWithPopup(flight.Estimated)),
        <.td(localDateTimeWithPopup(flight.Actual))
      )
      val estCell = List(<.td(localDateTimeWithPopup(flight.EstimatedChox)))
      val lastCells = List[TagMod](
        <.td(localDateTimeWithPopup(flight.ActualChox)),
        <.td(pcpTimeRange(flight, props.pcpPaxFn)),
        <.td(FlightComponents.paxComp(props.pcpPaxFn)(flightWithSplits))
      )
      val flightFields = if (props.hasEstChox) firstCells ++ estCell ++ lastCells else firstCells ++ lastCells

      val paxClass = FlightComponents.paxClassFromSplits(flightWithSplits)

      val flightId = flight.uniqueId.toString

      val timeLineTagMod = props.timelineComponent.map(timeline => <.td(timeline(flight))).toList.toTagMod

      val trClassName = s"${offScheduleClass(flight)} $timeIndicatorClass${if (flight.isCancelled) " arrival-cancelled" else ""}"

      val queueTagMod = props.splitsQueueOrder.map(q => <.td(<.span(s"${queuePax.getOrElse(q, 0)}"), ^.className := s"queue-split $paxClass ${q.toString.toLowerCase()}-queue-pax right")).toTagMod

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
    .configure(Reusability.shouldComponentUpdate)
    .build

  private def gateOrStand(walkTimes: WalkTimes, defaultWalkTime: Long, flight: Arrival): VdomTagOf[Span] = {
    val walkTimeProvider: (Option[String], Option[String], Terminal) => String =
      walkTimes.walkTimeForArrival(defaultWalkTime)
    val gateOrStand = <.span(s"${flight.Gate.getOrElse("")} / ${flight.Stand.getOrElse("")}")
    val gateOrStandWithWalkTimes = TippyJSComponent(
      <.span(walkTimeProvider(flight.Gate, flight.Stand, flight.Terminal)).rawElement,
      true,
      gateOrStand
    )
    val displayGatesOrStands = if (walkTimes.isEmpty) gateOrStand else <.span(gateOrStandWithWalkTimes)
    displayGatesOrStands
  }

  def offScheduleClass(arrival: Arrival): String = {
    val eta = bestArrivalTime(arrival)
    val differenceFromScheduled = eta - arrival.Scheduled
    val hourInMillis = 3600000
    val offScheduleClass = if (differenceFromScheduled > hourInMillis || differenceFromScheduled < -1 * hourInMillis)
      "danger"
    else ""
    offScheduleClass
  }

  def apply(props: Props): Unmounted[Props, RowState, Unit] = component(props)

}
