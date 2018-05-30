package drt.client.components

import drt.client.components.FlightComponents.SplitsGraph
import drt.client.components.FlightTableRow.SplitsGraphComponentFn
import drt.client.logger._
import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.QueueName
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{TagMod, TagOf}
import org.scalajs.dom.html.{Div, TableSection}

import scala.util.{Failure, Success, Try}

object FlightsWithSplitsTable {

  type BestPaxForArrivalF = (Arrival) => Int

  case class Props(flightsWithSplits: List[ApiFlightWithSplits], queueOrder: List[PaxTypeAndQueue], hasEstChox: Boolean)

  implicit val propsReuse: Reusability[Props] = Reusability.by((props: Props) => {
    props.flightsWithSplits.hashCode()
  })

  def ArrivalsTable(timelineComponent: Option[(Arrival) => VdomNode] = None,
                    originMapper: (String) => VdomNode = (portCode) => portCode,
                    splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div()
                   )(paxComponent: (ApiFlightWithSplits) => TagMod = (f) => f.apiFlight.ActPax.getOrElse(0).toInt) = ScalaComponent.builder[Props]("ArrivalsTable")
    .render_P((props) => {

      val flightsWithSplits = props.flightsWithSplits
      val flightsWithCodeShares: Seq[(ApiFlightWithSplits, Set[Arrival])] = FlightTableComponents.uniqueArrivalsWithCodeShares(flightsWithSplits)
      val sortedFlights = flightsWithCodeShares.sortBy(_._1.apiFlight.PcpTime)
      val isTimeLineSupplied = timelineComponent.isDefined
      val timelineTh = (if (isTimeLineSupplied) <.th("Timeline") :: Nil else List[TagMod]()).toTagMod
      val queueNames = ApiSplitsToSplitRatio.queuesFromPaxTypeAndQueue(props.queueOrder)

      Try {
        if (sortedFlights.nonEmpty) {
          val dataStickyAttr = VdomAttr("data-sticky") := "data-sticky"
          val classesAttr = ^.className := "table table-responsive table-striped table-hover table-sm"
          <.div(
            <.div(^.id := "toStick", ^.className := "container sticky",
              <.table(
                ^.id := "sticky",
                classesAttr,
                tableHead(props, timelineTh, queueNames))),
            <.table(
              ^.id := "sticky-body",
              dataStickyAttr,
              classesAttr,
              tableHead(props, timelineTh, queueNames),
              <.tbody(
                sortedFlights.zipWithIndex.map {
                  case ((flightWithSplits, codeShares), idx) =>
                    FlightTableRow.tableRow(FlightTableRow.Props(
                      flightWithSplits, codeShares, idx,
                      timelineComponent = timelineComponent,
                      originMapper = originMapper,
                      paxComponent = paxComponent,
                      splitsGraphComponent = splitsGraphComponent,
                      splitsQueueOrder = props.queueOrder,
                      hasEstChox = props.hasEstChox
                    ))
                }.toTagMod)))
        }
        else
          <.div("Loading flights...")
      } match {
        case Success(s) => s
        case Failure(f) =>
          log.error(s"failure in table render $f")
          <.div(s"render failure $f")
      }
    })
    .configure(Reusability.shouldComponentUpdate)
    .componentDidMount((_) => StickyTableHeader("[data-sticky]"))
    .build

  def tableHead(props: Props, timelineTh: TagMod, queueNames: Seq[String]): TagOf[TableSection] = {
    val columns = List(
      ("Flight", None),
      ("Origin", None),
      ("Gate/Stand", Option("gate-stand")),
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

  type OriginMapperF = (String) => VdomNode
  type BestPaxForArrivalF = (Arrival) => Int

  type SplitsGraphComponentFn = (SplitsGraph.Props) => TagOf[Div]

  case class Props(flightWithSplits: ApiFlightWithSplits,
                   codeShares: Set[Arrival],
                   idx: Int,
                   timelineComponent: Option[(Arrival) => VdomNode],
                   originMapper: OriginMapperF = (portCode) => portCode,
                   paxComponent: (ApiFlightWithSplits) => TagMod = (f) => f.apiFlight.ActPax.getOrElse(0).toInt,
                   splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div(),
                   splitsQueueOrder: List[PaxTypeAndQueue],
                   hasEstChox: Boolean
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

  val tableRow = ScalaComponent.builder[Props]("TableRow")
    .initialState[RowState](RowState(false))
    .render_PS((props, state) => {
      val codeShares = props.codeShares
      val flightWithSplits = props.flightWithSplits
      val flight = flightWithSplits.apiFlight
      val allCodes = flight.ICAO :: codeShares.map(_.ICAO).toList

      Try {
        def sourceDisplayName(splits: ApiSplits) = splits match {
          case ApiSplits(_, SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages, _, _) => s"Live ${splits.eventType.getOrElse("")}"
          case ApiSplits(_, SplitSources.Historical, _, _) => "Historical"
          case _ => "Port Average"
        }

        def GraphComponent(source: String, splitStyleUnitLabel: String, sourceDisplay: String, splitTotal: Int, queuePax: Map[PaxTypeAndQueue, Int], queueOrder: Seq[PaxTypeAndQueue]): VdomElement = {
          val orderedSplitCounts: Seq[(PaxTypeAndQueue, Int)] = queueOrder.map(ptq => ptq -> queuePax.getOrElse(ptq, 0))
          val tt = <.table(^.className := "table table-responsive table-hover table-sm ",
            <.thead(<.tr(<.th(splitStyleUnitLabel), <.th("PassengerType"), <.th("Queue"))),
            <.tbody(orderedSplitCounts.map(s => <.tr(<.td(s"${s._2}"), <.td(s._1.passengerType.name), <.td(s._1.queueType))).toTagMod))
          <.div(^.className := "splitsource-" + source,
            props.splitsGraphComponent(SplitsGraph.Props(splitTotal, orderedSplitCounts, Option(tt))),
            sourceDisplay)
        }

        val hasChangedStyle = if (state.hasChanged) ^.background := "rgba(255, 200, 200, 0.5) " else ^.outline := ""
        val timeIndicatorClass = if (flight.PcpTime.getOrElse(0L) < SDate.now().millisSinceEpoch) "before-now" else "from-now"

        val queueNames = ApiSplitsToSplitRatio.queuesFromPaxTypeAndQueue(props.splitsQueueOrder)
        val queuePax: Map[QueueName, Int] = ApiSplitsToSplitRatio.paxPerQueueUsingBestSplitsAsRatio(flightWithSplits).getOrElse(Map())
        val flightFields = List[(Option[String], TagMod)](
          (None, allCodes.mkString(" - ")),
          (None, props.originMapper(flight.Origin)),
          (None, s"${flight.Gate.getOrElse("")}/${flight.Stand.getOrElse("")}"),
          (None, flight.Status),
          (None, localDateTimeWithPopup(Option(flight.Scheduled))),
          (None, localDateTimeWithPopup(flight.Estimated)),
          (None, localDateTimeWithPopup(flight.Actual)),
          (Option("est-chox"), localDateTimeWithPopup(flight.EstimatedChox)),
          (None, localDateTimeWithPopup(flight.ActualChox)),
          (None, pcpTimeRange(flight, ArrivalHelper.bestPax)),
          (Option("right"), props.paxComponent(flightWithSplits)))
          .filterNot {
            case (Some("est-chox"), _) if !props.hasEstChox => true
            case _ => false
          }
          .map {
            case (Some(className), tm) => <.td(tm, ^.className := className)
            case (_, tm) => <.td(tm)
          }
          .toTagMod

        <.tr(
          ^.key := flight.uniqueId.toString,
          ^.className := s"${offScheduleClass(flight)} $timeIndicatorClass",
          hasChangedStyle,
          props.timelineComponent.map(timeline => <.td(timeline(flight))).toList.toTagMod,
          flightFields,
          queueNames.map(q => <.td(s"${queuePax.getOrElse(q, 0)}", ^.className := "right")).toTagMod
        )
      }.recover {
        case e => log.error(s"couldn't make flight row $e")
          <.tr(s"failure $e, ${e.getMessage} ${e.getStackTrace.mkString(",")}")
      }.get
    }

    )
    .componentDidMount((p) => Callback.log(s"arrival row component didMount"))
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
}

