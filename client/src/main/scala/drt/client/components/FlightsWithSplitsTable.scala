package drt.client.components

import drt.client.components.FlightComponents.SplitsGraph
import drt.client.components.FlightTableRow.SplitsGraphComponentFn
import drt.client.logger._
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{TagMod, TagOf}
import org.scalajs.dom.html.Div

import scala.util.{Failure, Success, Try}

object FlightsWithSplitsTable {

  type BestPaxForArrivalF = (Arrival) => Int

  case class Props(flightsWithSplits: List[ApiFlightWithSplits], bestPax: (Arrival) => Int, queueOrder: List[PaxTypeAndQueue])

  implicit val propsReuse: Reusability[Props] = Reusability.by((props: Props) => {
    props.flightsWithSplits.map(_.lastUpdated)
  })

  def ArrivalsTable(timelineComponent: Option[(Arrival) => VdomNode] = None,
                    originMapper: (String) => VdomNode = (portCode) => portCode,
                    splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div()
                   )(paxComponent: (Arrival, ApiSplits) => TagMod = (f, _) => f.ActPax) = ScalaComponent.builder[Props]("ArrivalsTable")
    .renderPS((_$, props, state) => {


      val flightsWithSplits = props.flightsWithSplits
      val bestPax = props.bestPax
      val flightsWithCodeShares: Seq[(ApiFlightWithSplits, Set[Arrival])] = FlightTableComponents.uniqueArrivalsWithCodeShares(flightsWithSplits)
      val sortedFlights = flightsWithCodeShares.sortBy(_._1.apiFlight.PcpTime)
      val isTimeLineSupplied = timelineComponent.isDefined
      val timelineTh = (if (isTimeLineSupplied) <.th("Timeline") :: Nil else List[TagMod]()).toTagMod
      Try {
        if (sortedFlights.nonEmpty)
          <.div(
            <.table(
              ^.className := "table table-responsive table-striped table-hover table-sm",
              <.thead(<.tr(
                timelineTh,
                <.th("Flight"), <.th("Origin"),
                <.th("Gate/Stand"),
                <.th("Status"),
                <.th("Sch"),
                <.th("Est"),
                <.th("Act"),
                <.th("Est Chox"),
                <.th("Act Chox"),
                <.th("Est PCP"),
                <.th("Pax Nos"),
                <.th("Splits")
              )),
              <.tbody(
                sortedFlights.zipWithIndex.map {
                  case ((flightWithSplits, codeShares), idx) =>
                    FlightTableRow.tableRow(FlightTableRow.Props(
                      flightWithSplits, codeShares, idx,
                      timelineComponent = timelineComponent,
                      originMapper = originMapper,
                      paxComponent = paxComponent,
                      splitsGraphComponent = splitsGraphComponent,
                      bestPax = bestPax,
                      splitsQueueOrder = props.queueOrder
                    ))
                }.toTagMod)))
        else
          <.div("No flights in this time period")
      } match {
        case Success(s) => s
        case Failure(f) =>
          log.error(s"failure in table render $f")
          <.div(s"render failure $f")
      }
    })
    .configure(Reusability.shouldComponentUpdate)
    .build

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
                   paxComponent: (Arrival, ApiSplits) => TagMod = (f, _) => f.ActPax,
                   splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div(),
                   splitsQueueOrder: List[PaxTypeAndQueue],
                   bestPax: (Arrival) => Int
                  )

  implicit val propsReuse: Reusability[Props] = Reusability.by((props: Props) => props.flightWithSplits.lastUpdated)
  implicit val stateReuse: Reusability[RowState] = Reusability.caseClass[RowState]

  case class RowState(hasChanged: Boolean)

  val tableRow = ScalaComponent.builder[Props]("TableRow")
    .initialState[RowState](RowState(false))
    .renderPS(($, props, state) => {

      val idx = props.idx
      val codeShares = props.codeShares
      val flightWithSplits = props.flightWithSplits
      val flight = flightWithSplits.apiFlight
      val allCodes = flight.ICAO :: codeShares.map(_.ICAO).toList

      Try {
        def sourceDisplayName(splits: ApiSplits) =
          splits match {
            case ApiSplits(_, SplitSources.ApiSplitsWithCsvPercentage, _, _) => s"Live ${splits.eventType.getOrElse("")}"
            case ApiSplits(_, SplitSources.Historical, _, _) => "Historical"
            case _ => "Port Average"
          }

        def GraphComponent(source: String, splitStyleUnitLabel: String, sourceDisplay: String, splitTotal: Int, queuePax: Map[PaxTypeAndQueue, Int], queueOrder: Seq[PaxTypeAndQueue]): VdomElement = {
          val orderedSplitCounts: Seq[(PaxTypeAndQueue, Int)] = queueOrder.map(ptq => ptq -> queuePax.getOrElse(ptq, 0))
          val tt = <.table(^.className := "table table-responsive table-striped table-hover table-sm ",
            <.thead(<.tr(<.th(splitStyleUnitLabel), <.th("PassengerType"), <.th("Queue"))),
            <.tbody(orderedSplitCounts.map(s => <.tr(<.td(s"${s._2}"), <.td(s._1.passengerType.name), <.td(s._1.queueType))).toTagMod))
          <.div(^.className := "splitsource-" + source,
            props.splitsGraphComponent(SplitsGraph.Props(splitTotal, orderedSplitCounts, Option(tt))),
            sourceDisplay)
        }

        val bestSplits = flightWithSplits.bestSplits.toSet
        //todo - we need to lift this splitsComponent code out somewhere more useful
        val splitsComponents = bestSplits.map {
          flightSplits => {
            val splitStyle = flightSplits.splitStyle
            val source = flightSplits.source.toLowerCase
            val sourceDisplay = sourceDisplayName(flightSplits)
            val vdomElement: VdomElement = splitStyle match {
              case PaxNumbers =>
                val splitTotal = flightSplits.splits.map(_.paxCount.toInt).sum
                val splitStyleUnitLabe = "pax"
                val queuePax: Map[PaxTypeAndQueue, Int] = flightSplits.splits.map({
                  case s if splitStyle == PaxNumbers => PaxTypeAndQueue(s.passengerType, s.queueType) -> s.paxCount.toInt
                }).toMap
                GraphComponent(source, splitStyleUnitLabe, sourceDisplay, splitTotal, queuePax, PaxTypesAndQueues.inOrderSansFastTrack)
              case Percentage =>
                val splitTotal = flightSplits.splits.map(_.paxCount.toInt).sum
                val splitStyle = flightSplits.splitStyle
                val splitStyleUnitLabe = "%"
                val queuePax: Map[PaxTypeAndQueue, Int] = flightSplits.splits.map({
                  case s if splitStyle == Percentage => PaxTypeAndQueue(s.passengerType, s.queueType) -> s.paxCount.toInt
                }).toMap
                GraphComponent(source, splitStyleUnitLabe, sourceDisplay, splitTotal, queuePax, PaxTypesAndQueues.inOrderSansFastTrack)
              case UndefinedSplitStyle => <.div(s"$splitStyle")
            }
            vdomElement
          }
        }

        val hasChangedStyle = if (state.hasChanged) ^.background := "rgba(255, 200, 200, 0.5) " else ^.outline := ""
        val apiSplits = flightWithSplits.splits
          .find(splits => splits.source == SplitRatiosNs.SplitSources.ApiSplitsWithCsvPercentage)
          .getOrElse(ApiSplits(Set(), "no splits - client", None))

        <.tr(^.key := flight.FlightID.toString,
          hasChangedStyle,
          props.timelineComponent.map(timeline => <.td(timeline(flight))).toList.toTagMod,
          <.td(^.key := flight.FlightID.toString + "-flightNo", allCodes.mkString(" - ")),
          <.td(^.key := flight.FlightID.toString + "-origin", props.originMapper(flight.Origin)),
          <.td(^.key := flight.FlightID.toString + "-gatestand", s"${flight.Gate}/${flight.Stand}"),
          <.td(^.key := flight.FlightID.toString + "-status", flight.Status),
          <.td(^.key := flight.FlightID.toString + "-schdt", localDateTimeWithPopup(flight.SchDT)),
          <.td(^.key := flight.FlightID.toString + "-estdt", localDateTimeWithPopup(flight.EstDT)),
          <.td(^.key := flight.FlightID.toString + "-actdt", localDateTimeWithPopup(flight.ActDT)),
          <.td(^.key := flight.FlightID.toString + "-estchoxdt", localDateTimeWithPopup(flight.EstChoxDT)),
          <.td(^.key := flight.FlightID.toString + "-actchoxdt", localDateTimeWithPopup(flight.ActChoxDT)),
          <.td(^.key := flight.FlightID.toString + "-pcptimefrom", pcpTimeRange(flight, props.bestPax)),
          <.td(^.key := flight.FlightID.toString + "-actpax", props.paxComponent(flight, apiSplits)),
          <.td(^.key := flight.FlightID.toString + "-splits", splitsComponents.toTagMod))
      }.recover {
        case e => log.error(s"couldn't make flight row $e")
          <.tr(s"failure $e, ${e.getMessage} ${e.getStackTrace.mkString(",")}")
      }.get
    }

    )
    .componentDidMount((p) => Callback.log(s"arrival row component didMount"))
    .configure(Reusability.shouldComponentUpdate)
    .build
}





