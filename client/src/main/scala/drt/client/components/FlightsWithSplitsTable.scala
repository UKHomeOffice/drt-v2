package drt.client.components

import drt.client.components.FlightComponents.SplitsGraph
import drt.client.components.FlightTableRow.SplitsGraphComponentFn
import drt.client.logger
import drt.client.logger._
import drt.client.services.JSDateConversions.SDate
import drt.shared.FlightsApi.FlightsWithSplits
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

  case class Props(flightsWithSplits: FlightsWithSplits, bestPax: (Arrival) => Int, queueOrder: List[PaxTypeAndQueue])

  //  implicit val paxTypeReuse = Reusability.byRef[PaxType]
  //  implicit val doubleReuse = Reusability.double(0.001)
  //  implicit val splitStyleReuse = Reusability.byRef[SplitStyle]
  //  implicit val paxtypeandQueueReuse = Reusability.caseClass[ApiPaxTypeAndQueueCount]
  //  implicit val SplitsReuse = Reusability.caseClass[ApiSplits]
  //  implicit val flightReuse = Reusability.caseClass[Arrival]
  //  implicit val apiflightsWithSplitsReuse = Reusability.caseClass[ApiFlightWithSplits]
  //  implicit val flightsWithSplitsReuse = Reusability.caseClass[FlightsWithSplits]
  //  implicit val paxTypeAndQueueReuse = Reusability.caseClass[PaxTypeAndQueue]
  //  implicit val bestPaxReuse = Reusability.byRefOr_==[BestPaxForArrivalF]

  //  implicit val flightReuse = Reusability.caseClassExcept[Arrival]('Operator, 'MaxPax, 'RunwayID, 'BaggageReclaimId, 'FlightID, 'AirportID)
  //  implicit val apiflightsWithSplitsReuse = Reusability.caseClassExcept[ApiFlightWithSplits]('splits)
  //  implicit val flightsWithSplitsReuse = Reusability.caseClass[FlightsWithSplits]
  //  implicit val propsReuse = Reusability.caseClassExcept[Props]('queueOrder, 'bestPax)

  //  implicit val apiFlightWithSplitsReuse = Reusability.by((fp: ApiFlightWithSplits) => {
  //    fp.map(f => f.flights.map(af => {
  //      (af.apiFlight.PcpTime, af.splits.map(_.hashCode()))
  //    }))
  //  })
  implicit val propsReuse = Reusability.by((props: Props) => {
    props.flightsWithSplits.flights.map(af => {
      (af.splits.hashCode(), af.apiFlight.PcpTime, af.apiFlight.Status, af.apiFlight.Gate, af.apiFlight.Stand, af.apiFlight.ActChoxDT)
    })
  })

  def ArrivalsTable(timelineComponent: Option[(Arrival) => VdomNode] = None,
                    originMapper: (String) => VdomNode = (portCode) => portCode,
                    paxComponent: (Arrival, ApiSplits) => TagMod = (f, _) => f.ActPax,
                    splitsGraphComponent: SplitsGraphComponentFn = (_: SplitsGraph.Props) => <.div()
                   ) = ScalaComponent.builder[Props]("ArrivalsTable")

    .renderP((_$, props) => {
      val flightsWithSplits = props.flightsWithSplits
      val bestPax = props.bestPax
      val flightsWithCodeShares: Seq[(ApiFlightWithSplits, Set[Arrival])] = FlightTableComponents.uniqueArrivalsWithCodeShares(flightsWithSplits.flights)
      val sortedFlights = flightsWithCodeShares.sortBy(_._1.apiFlight.PcpTime) //todo move this closer to the model
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
                  case ((flightWithSplits, codeShares), idx) => {
                    log.info(s"looking at rendering flight ${flightWithSplits.apiFlight.IATA}")
                    FlightTableRow.tableRow(FlightTableRow.Props(
                      flightWithSplits, codeShares, idx,
                      timelineComponent = timelineComponent,
                      originMapper = originMapper,
                      paxComponent = paxComponent,
                      splitsGraphComponent = splitsGraphComponent,
                      bestPax = bestPax,
                      splitsQueueOrder = props.queueOrder
                    ))
                  }
                }.toTagMod)))
        else
          <.div("No flights in this time period")
      } match {
        case Success(s) => s
        case Failure(f) =>
          log.error(s"failure in table render $f")
          <.div(s"render failure ${f}")
      }
    })
    .componentDidMount((p) => Callback.log(s"arrivals table didMount"))
    .configure(Reusability.shouldComponentUpdateWithOverlay)
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

  //  implicit val splitStyleReuse = Reusability.byRef[SplitStyle]
  //  implicit val paxTypeReuse = Reusability.byRef[PaxType]
  //  implicit val doubleReuse = Reusability.double(0.01)
  //  implicit val paxTypeAndQueueCountReuse = Reusability.caseClass[ApiPaxTypeAndQueueCount]
  //  implicit val paxTypeAndQueueReuse = Reusability.caseClass[PaxTypeAndQueue]
  ////    implicit val flightReuse = Reusability.caseClass[List[ApiPaxTypeAndQueueCount]]
  ////    implicit val flightReuse = Reusability.caseClass[List[Arrival]]
  //  implicit val SplitsReuse = Reusability.caseClass[ApiSplits]
  //  implicit val flightReuse = Reusability.caseClass[Arrival]
  //  implicit val apiflightsWithSplitsReuse = Reusability.caseClass[ApiFlightWithSplits]
  //  implicit val flightsWithSplitsReuse = Reusability.caseClass[FlightsWithSplits]
  //
  //  implicit val originMapperReuse = Reusability.byRefOr_==[OriginMapperF]
  //  implicit val arrivalToPax = Reusability.byRefOr_==[BestPaxForArrivalF]

//  implicit val flightReuse = Reusability.caseClassExcept[Arrival]('Operator, 'MaxPax, 'RunwayID, 'BaggageReclaimId, 'FlightID,
//    'AirportID, 'LastKnownPax, 'Terminal, 'PcpTime, 'rawIATA, 'rawICAO)
//  implicit val apiflightsWithSplitsReuse = Reusability.caseClassExcept[ApiFlightWithSplits]('splits)
//  implicit val propsReuse = Reusability.caseClassExcept[Props]('codeShares, 'idx, 'timelineComponent, 'originMapper, 'paxComponent, 'splitsGraphComponent, 'splitsQueueOrder, 'bestPax)

  implicit val propsReuse = Reusability.by((props: Props) => {
    (props.flightWithSplits.splits.hashCode(), props.flightWithSplits.apiFlight.PcpTime)
  })

  //  case class RowState(hasChanged: Boolean)

  //  implicit val stateReuse = Reusability.caseClass[RowState]

  val tableRow = ScalaComponent.builder[Props]("TableRow")
    //    .initialState[RowState](RowState(false))
    .render_P(props => {

    val idx = props.idx
    val codeShares = props.codeShares
    val flightWithSplits = props.flightWithSplits
    log.info(s"actually rendering flight ${flightWithSplits.apiFlight.IATA}")
    val flight = flightWithSplits.apiFlight
    val allCodes = flight.ICAO :: codeShares.map(_.ICAO).toList

    //      log.debug(s"rendering flight row $idx ${flight.toString}")
    Try {
      val flightSplitsList: List[ApiSplits] = flightWithSplits.splits

      def sourceDisplayName(name: String) = Map(SplitSources.AdvPaxInfo -> "Live",
        SplitSources.ApiSplitsWithCsvPercentage -> "Live",
        SplitSources.Historical -> "Historical"
      ).getOrElse(name, name)

      def GraphComponent(source: String, splitStyleUnitLabel: String, sourceDisplay: String, splitTotal: Int, queuePax: Map[PaxTypeAndQueue, Int], queueOrder: Seq[PaxTypeAndQueue]): VdomElement = {
        val orderedSplitCounts: Seq[(PaxTypeAndQueue, Int)] = queueOrder.map(ptq => ptq -> queuePax.getOrElse(ptq, 0))
        val tt = <.table(^.className := "table table-responsive table-striped table-hover table-sm ",
          <.thead(<.tr(<.th(splitStyleUnitLabel), <.th("PassengerType"), <.th("Queue"))),
          <.tbody(orderedSplitCounts.map(s => <.tr(<.td(s"${s._2}"), <.td(s._1.passengerType.name), <.td(s._1.queueType))).toTagMod))
        <.div(^.className := "splitsource-" + source,
          props.splitsGraphComponent(SplitsGraph.Props(splitTotal, orderedSplitCounts, tt)),
          sourceDisplay)
      }

      //todo - we need to lift this splitsComponent code out somewhere more useful
      val splitsComponents = flightSplitsList.take(1).map {
        flightSplits => {
          val splitStyle = flightSplits.splitStyle
          val source = flightSplits.source.toLowerCase
          val sourceDisplay = sourceDisplayName(flightSplits.source)
          val vdomElement: VdomElement = splitStyle match {
            case PaxNumbers => {
              val splitTotal = flightSplits.splits.map(_.paxCount.toInt).sum
              val splitStyleUnitLabe = "pax"
              val queuePax: Map[PaxTypeAndQueue, Int] = flightSplits.splits.map({
                case s if splitStyle == PaxNumbers => PaxTypeAndQueue(s.passengerType, s.queueType) -> s.paxCount.toInt
              }).toMap
              GraphComponent(source, splitStyleUnitLabe, sourceDisplay, splitTotal, queuePax, PaxTypesAndQueues.inOrderSansFastTrack)
            }
            case Percentage => {
              val splitTotal = flightSplits.splits.map(_.paxCount.toInt).sum
              val splitStyle = flightSplits.splitStyle
              val splitStyleUnitLabe = "%"
              val queuePax: Map[PaxTypeAndQueue, Int] = flightSplits.splits.map({
                case s if splitStyle == Percentage => PaxTypeAndQueue(s.passengerType, s.queueType) -> s.paxCount.toInt
              }).toMap
              GraphComponent(source, splitStyleUnitLabe, sourceDisplay, splitTotal, queuePax, PaxTypesAndQueues.inOrderSansFastTrack)
            }
          }
          vdomElement
        }
      }

      //        val hasChangedStyle = if (state.hasChanged) ^.background := "rgba(255, 200, 200, 0.5) " else ^.outline := ""
      val hasChangedStyle = ^.outline := ""
      val apiSplits = flightWithSplits.splits
        .find(splits => splits.source == SplitRatiosNs.SplitSources.ApiSplitsWithCsvPercentage)
        .getOrElse(ApiSplits(Nil, "no splits - client"))

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
        <.tr(s"failure $e, ${e.getMessage} ${e.getStackTrace().mkString(",")}")
    }.get
  }

  )
    .componentDidMount((p) => Callback.log(s"arrival row component didMount"))
    .componentDidMount((p) => Callback.log(s"arrivals row didMount"))
    .configure(Reusability.shouldComponentUpdateWithOverlay)
    .build


}





