package drt.client.components

import diode.data.Pot
import diode.react.ModelProxy
import drt.client.components.FlightComponents.SplitsGraph
import drt.client.components.FlightTableRow.SplitsGraphComponentFn
import drt.client.logger._
import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.QueueName
import drt.shared._
import drt.shared.splits.ApiSplitsToSplitRatio
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{TagMod, TagOf}
import japgolly.scalajs.react.{CtorType, _}
import org.scalajs.dom.html.{Div, TableSection}

import scala.util.{Failure, Success, Try}

object FlightsWithSplitsTable {

  type BestPaxForArrivalF = Arrival => Int

  case class Props(
                    flightsWithSplits: List[ApiFlightWithSplits],
                    queueOrder: Seq[QueueName],
                    hasEstChox: Boolean,
                    useApiPaxNos: Boolean
                  )

  implicit val propsReuse: Reusability[Props] = Reusability.by((props: Props) => {
    props.flightsWithSplits.hashCode()
  })

  def ArrivalsTable(timelineComponent: Option[Arrival => VdomNode] = None,
                    originMapper: String => VdomNode = portCode => portCode,
                    splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div()
                   )(paxComponent: ApiFlightWithSplits => TagMod): Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props](displayName = "ArrivalsTable")
    .render_P(props => {

      val flightsWithSplits = props.flightsWithSplits
      val flightsWithCodeShares: Seq[(ApiFlightWithSplits, Set[Arrival])] = FlightTableComponents.uniqueArrivalsWithCodeShares(flightsWithSplits)
      val sortedFlights = flightsWithCodeShares.sortBy(_._1.apiFlight.PcpTime)
      val isTimeLineSupplied = timelineComponent.isDefined
      val timelineTh = (if (isTimeLineSupplied) <.th("Timeline") :: Nil else List[TagMod]()).toTagMod

      Try {
        if (sortedFlights.nonEmpty) {
          val dataStickyAttr = VdomAttr("data-sticky") := "data-sticky"
          val classesAttr = ^.className := "table table-responsive table-striped table-hover table-sm"
          <.div(
            <.div(^.id := "toStick", ^.className := "container sticky",
              <.table(
                ^.id := "sticky",
                classesAttr,
                tableHead(props, timelineTh, props.queueOrder))),
            <.table(
              ^.id := "sticky-body",
              dataStickyAttr,
              classesAttr,
              tableHead(props, timelineTh, props.queueOrder),
              <.tbody(
                sortedFlights.zipWithIndex.map {
                  case ((flightWithSplits, codeShares), idx) =>
                    FlightTableRow.component(FlightTableRow.Props(
                      flightWithSplits, codeShares, idx,
                      timelineComponent = timelineComponent,
                      originMapper = originMapper,
                      paxComponent = paxComponent,
                      splitsGraphComponent = splitsGraphComponent,
                      splitsQueueOrder = props.queueOrder,
                      hasEstChox = props.hasEstChox,
                      useApiPaxNos = props.useApiPaxNos
                    ))
                }.toTagMod)))
        }
        else
          <.div("No flights to display")
      } match {
        case Success(s) => s
        case Failure(f) =>
          log.error(msg = s"failure in table render $f")
          <.div(s"render failure $f")
      }
    })
    .configure(Reusability.shouldComponentUpdate)
    .componentDidMount(_ => StickyTableHeader("[data-sticky]"))
    .build

  def tableHead(props: Props, timelineTh: TagMod, queueNames: Seq[String]): TagOf[TableSection] = {
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
      ("Est PCP", Option("pcp")),
      ("Pax", None))

    val portColumnThs = columns
      .filter {
        case (label, _) => label != "Est Chox" || props.hasEstChox
      }
      .map {
        case (label, None) => <.th(label)
        case (label, Some(className)) => <.th(label, ^.className := className)
      }
      .toTagMod

    <.thead(
      <.tr(
        timelineTh,
        portColumnThs,
        queueNames.map(
          q => <.th(Queues.queueDisplayNames(q))
        ).toTagMod
      )
    )
  }
}

object FlightTableRow {

  import FlightTableComponents._

  type OriginMapperF = String => VdomNode
  type BestPaxForArrivalF = Arrival => Int

  type SplitsGraphComponentFn = SplitsGraph.Props => TagOf[Div]

  case class Props(flightWithSplits: ApiFlightWithSplits,
                   codeShares: Set[Arrival],
                   idx: Int,
                   timelineComponent: Option[Arrival => VdomNode],
                   originMapper: OriginMapperF = portCode => portCode,
                   paxComponent: ApiFlightWithSplits => TagMod,
                   splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div(),
                   splitsQueueOrder: Seq[QueueName],
                   hasEstChox: Boolean,
                   useApiPaxNos: Boolean
                  )

  case class RowState(hasChanged: Boolean)

  implicit val propsReuse: Reusability[Props] = Reusability.by(p => (p.flightWithSplits.hashCode, p.idx))
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
      val allCodes = flight.ICAO :: codeShares.map(_.ICAO).toList

      val hasChangedStyle = if (state.hasChanged) ^.background := "rgba(255, 200, 200, 0.5) " else ^.outline := ""
      val timeIndicatorClass = if (flight.PcpTime.getOrElse(0L) < SDate.now().millisSinceEpoch) "before-now" else "from-now"

      val queuePax: Map[QueueName, Int] = ApiSplitsToSplitRatio
        .paxPerQueueUsingBestSplitsAsRatio(ArrivalHelper.bestPax(props.useApiPaxNos))(flightWithSplits).getOrElse(Map())
      val flightFields = List[(Option[String], TagMod)](
        (None, allCodes.mkString(" - ")),
        (None, props.originMapper(flight.Origin)),
        (None, TerminalContentComponent.airportWrapper(flight.Origin) { proxy: ModelProxy[Pot[AirportInfo]] =>
          <.span(
            proxy().renderEmpty(<.span()),
            proxy().render(ai => <.span(ai.country))
          )
        }),
        (None, s"${flight.Gate.getOrElse("")}/${flight.Stand.getOrElse("")}"),
        (None, flight.Status),
        (None, localDateTimeWithPopup(Option(flight.Scheduled))),
        (None, localDateTimeWithPopup(flight.Estimated)),
        (None, localDateTimeWithPopup(flight.Actual)),
        (Option("est-chox"), localDateTimeWithPopup(flight.EstimatedChox)),
        (None, localDateTimeWithPopup(flight.ActualChox)),
        (None, pcpTimeRange(flight, ArrivalHelper.bestPax(props.useApiPaxNos))),
        (Option("right"), props.paxComponent(flightWithSplits))
      )
        .filterNot {
          case (Some("est-chox"), _) if !props.hasEstChox => true
          case _ => false
        }
        .map {
          case (Some(className), tm) => <.td(tm, ^.className := className)
          case (_, tm) => <.td(tm)
        }
        .toTagMod

      val paxClass = FlightComponents.paxClassFromSplits(flightWithSplits)

      <.tr(
        ^.key := flight.uniqueId.toString,
        ^.className := s"${offScheduleClass(flight)} $timeIndicatorClass",
        hasChangedStyle,
        props.timelineComponent.map(timeline => <.td(timeline(flight))).toList.toTagMod,
        flightFields,
        props.splitsQueueOrder.map(q => <.td(<.span(s"${queuePax.getOrElse(q, 0)}"), ^.className := s"queue-split $paxClass right")).toTagMod
      )
    })
    .configure(Reusability.shouldComponentUpdate)
    .build

  def offScheduleClass(arrival: Arrival): String = {
    val eta = bestArrivalTime(arrival)
    val differenceFromScheduled = eta - arrival.Scheduled
    val hourInMillis = 3600000
    val offScheduleClass = if (differenceFromScheduled > hourInMillis || differenceFromScheduled < -1 * hourInMillis)
      "danger"
    else ""
    offScheduleClass
  }

  def apply(props: Props) = component(props)
}

