package drt.client.components

import drt.shared._
import diode.data.{Pot, Ready}
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import drt.client.logger
import drt.client.modules.{GriddleComponentWrapper, ViewTools}
import drt.client.services.JSDateConversions.SDate
import drt.shared.FlightsApi.FlightsWithSplits
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.{TagMod, TagOf}

import scala.collection.mutable
import scala.scalajs.js
import scala.scalajs.js.Dictionary
import scala.scalajs.js.annotation.{JSExportAll, ScalaJSDefined}
import scala.util.{Failure, Success, Try}
import logger._
import org.scalajs.dom.html.Div
import drt.client.components.FlightTableComponents
import drt.shared.SplitRatiosNs.SplitSources

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

object FlightsWithSplitsTable {

  case class Props(flightsWithSplits: FlightsWithSplits)


  implicit val paxTypeReuse = Reusability.byRef[PaxType]
  implicit val doubleReuse = Reusability.double(0.001)
  implicit val splitStyleReuse = Reusability.byRef[SplitStyle]
  implicit val paxtypeandQueueReuse = Reusability.caseClassDebug[ApiPaxTypeAndQueueCount]
  //  implicit val flightReuse = Reusability.caseClassDebug[List[ApiPaxTypeAndQueueCount]]
  //  implicit val flightReuse = Reusability.caseClassDebug[List[ApiFlight]]
  implicit val SplitsReuse = Reusability.caseClassDebug[ApiSplits]
  implicit val flightReuse = Reusability.caseClassDebug[ApiFlight]
  implicit val apiflightsWithSplitsReuse = Reusability.caseClassDebug[ApiFlightWithSplits]
  implicit val flightsWithSplitsReuse = Reusability.caseClassDebug[FlightsWithSplits]

  //  implicit val listOfapiflightsWithSplitsReuse = Reusability.caseClassDebug[ApiFlightWithSplits]
  //  implicit val flightsWithSplitsReuse = Reusability.caseClassDebug[List[FlightsWithSplits]]
  implicit val propsReuse = Reusability.caseClassDebug[Props]


  def ArrivalsTable[C](timelineComponent: Option[(ApiFlight) => VdomNode] = None,
                       originMapper: (String) => VdomNode = (portCode) => portCode,
                       paxComponent: (ApiFlight, ApiSplits) => TagMod = (f, _) => f.ActPax,
                       splitsGraphComponent: (Int, Seq[(String, Int)]) => TagOf[Div] = (splitTotal: Int, splits: Seq[(String, Int)]) => <.div()
                      ) = ScalaComponent.builder[Props]("ArrivalsTable")

    .renderP((_$, props) => {
      log.info(s"sorting flights")
      val flightsWithSplits = props.flightsWithSplits
      val flightsWithCodeShares: Seq[(ApiFlightWithSplits, Set[ApiFlight])] = FlightTableComponents.uniqueArrivalsWithCodeShares(flightsWithSplits.flights)

      val sortedFlights = flightsWithCodeShares.sortBy(_._1.apiFlight.SchDT) //todo move this closer to the model
      log.info(s"sorted flights")
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
                <.th("Pcp"),
                <.th("Est"),
                <.th("Act"),
                <.th("Est Chox"),
                <.th("Act Chox"),
                <.th("Pax Nos"),
                <.th("Splits")
              )),
              <.tbody(
                sortedFlights.zipWithIndex.map {
                  case ((flightWithSplits, codeShares), idx) => {
                    FlightTableRow.tableRow(FlightTableRow.Props(
                      flightWithSplits, codeShares, idx,
                      timelineComponent = timelineComponent,
                      originMapper = originMapper,
                      paxComponent = paxComponent,
                      splitsGraphComponent = splitsGraphComponent
                    ))
                  }
                }.toTagMod)))
        else
          <.div("No flights in this time period")
      } match {
        case Success(s) =>
          log.info(s"table rendered!!")
          s

        case Failure(f) =>
          log.error(s"failure in table render $f")
          <.div(s"render failure ${f}")
      }
    })
    .componentDidMount((props) => Callback.log(s"componentDidMount! $props"))
    .configure(Reusability.shouldComponentUpdate)
    .build

}


object FlightTableRow {

  import FlightTableComponents._

  type OriginMapperF = (String) => VdomNode

  case class Props(flightWithSplits: ApiFlightWithSplits,
                   codeShares: Set[ApiFlight],
                   idx: Int,
                   timelineComponent: Option[(ApiFlight) => VdomNode],
                   originMapper: OriginMapperF = (portCode) => portCode,
                   paxComponent: (ApiFlight, ApiSplits) => TagMod = (f, _) => f.ActPax,
                   splitsGraphComponent: (Int, Seq[(String, Int)]) => TagOf[Div] = (splitTotal: Int, splits: Seq[(String, Int)]) => <.div())

  implicit val splitStyleReuse = Reusability.byRef[SplitStyle]
  implicit val paxTypeReuse = Reusability.byRef[PaxType]
  implicit val doubleReuse = Reusability.double(0.01)
  implicit val paxtypeandQueueReuse = Reusability.caseClassDebug[ApiPaxTypeAndQueueCount]
  //  implicit val flightReuse = Reusability.caseClassDebug[List[ApiPaxTypeAndQueueCount]]
  //  implicit val flightReuse = Reusability.caseClassDebug[List[ApiFlight]]
  implicit val SplitsReuse = Reusability.caseClassDebug[ApiSplits]
  implicit val flightReuse = Reusability.caseClassDebug[ApiFlight]
  implicit val apiflightsWithSplitsReuse = Reusability.caseClassDebug[ApiFlightWithSplits]
  implicit val flightsWithSplitsReuse = Reusability.caseClassDebug[FlightsWithSplits]

  implicit val originMapperReuse = Reusability.byRefOr_==[OriginMapperF]
  implicit val propsReuse = Reusability.caseClassExceptDebug[Props]('timelineComponent, 'paxComponent, 'splitsGraphComponent)

  case class RowState(hasChanged: Boolean)

  implicit val stateReuse = Reusability.caseClass[RowState]


  val tableRow = ScalaComponent.builder[Props]("TableRow")
    .initialState[RowState](RowState(false))
    .renderPS((_$, props, state) => {
      val idx = props.idx
      val codeShares = props.codeShares
      val flightWithSplits = props.flightWithSplits
      val flight = flightWithSplits.apiFlight
      val allCodes = flight.ICAO :: codeShares.map(_.ICAO).toList

      log.info(s"rendering flight row $idx ${flight.toString}")
      Try {
        val flightSplitsList: List[ApiSplits] = flightWithSplits.splits

        def sourceDisplayName(name: String) = Map(SplitSources.AdvPaxInfo -> "Live",
          SplitSources.ApiSplitsWithCsvPercentage -> "Live",
          SplitSources.Historical -> "Historical"
        ).getOrElse(name, name)

        def GraphComponent(source: String, splitStyleUnitLabe: String, sourceDisplay: String, splitTotal: Int, queuePax: Map[PaxTypeAndQueue, Int]) = {
          val orderedSplitCounts: Seq[(PaxTypeAndQueue, Int)] = PaxTypesAndQueues.inOrder.map(ptq => ptq -> queuePax.getOrElse(ptq, 0))
          val splitsAndLabels: Seq[(String, Int)] = orderedSplitCounts.map {
            case (ptqc, paxCount) => (s"$splitStyleUnitLabe ${ptqc.passengerType} > ${ptqc.queueType}", paxCount)
          }
          <.div(^.className := "splitsource-" + source,
            props.splitsGraphComponent(splitTotal, splitsAndLabels), sourceDisplay)
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
                GraphComponent(source, splitStyleUnitLabe, sourceDisplay, splitTotal, queuePax)
              }
              case Percentage => {
                val splitTotal = flightSplits.splits.map(_.paxCount.toInt).sum
                val splitStyle = flightSplits.splitStyle
                val splitStyleUnitLabe = "%"
                val queuePax: Map[PaxTypeAndQueue, Int] = flightSplits.splits.map({
                  case s if splitStyle == Percentage => PaxTypeAndQueue(s.passengerType, s.queueType) -> s.paxCount.toInt
                }).toMap
                GraphComponent(source, splitStyleUnitLabe, sourceDisplay, splitTotal, queuePax)
              }
            }
            vdomElement
          }
        }

        val hasChangedStyle = if (state.hasChanged) ^.background := "rgba(255, 200, 200, 0.5) " else ^.outline := ""
        val apiSplits = flightWithSplits.splits
          .find(splits => splits.source == SplitRatiosNs.SplitSources.ApiSplitsWithCsvPercentage)
          .getOrElse(ApiSplits(Nil, "no splits - client"))

        val triedMod: Try[TagMod] = {
          log.info(s"tryingpcp ${flight.PcpTime}")
          val pcpDate = SDate(MilliDate(flight.PcpTime))
          log.info(s"tryingpcpd ${pcpDate}")
          Try(localDateTimeWithPopup(pcpDate.toApiFlightString()))
        }
        val pcpTime: TagMod = triedMod.recoverWith{
          case f => Try(<.span(f.toString, s"in flight $flight"))
        }.get

        <.tr(^.key := flight.FlightID.toString,
          hasChangedStyle,
          props.timelineComponent.map(timeline => <.td(timeline(flight))).toList.toTagMod,
          <.td(^.key := flight.FlightID.toString + "-flightNo", allCodes.mkString(" - ")),
          <.td(^.key := flight.FlightID.toString + "-origin", props.originMapper(flight.Origin)),
          <.td(^.key := flight.FlightID.toString + "-gatestand", s"${flight.Gate}/${flight.Stand}"),
          <.td(^.key := flight.FlightID.toString + "-status", flight.Status),
          <.td(^.key := flight.FlightID.toString + "-schdt", localDateTimeWithPopup(flight.SchDT)),
          <.td(^.key := flight.FlightID.toString + "-pcptime", pcpTime),
          <.td(^.key := flight.FlightID.toString + "-estdt", localDateTimeWithPopup(flight.EstDT)),
          <.td(^.key := flight.FlightID.toString + "-actdt", localDateTimeWithPopup(flight.ActDT)),
          <.td(^.key := flight.FlightID.toString + "-estchoxdt", localDateTimeWithPopup(flight.EstChoxDT)),
          <.td(^.key := flight.FlightID.toString + "-actchoxdt", localDateTimeWithPopup(flight.ActChoxDT)),
          <.td(^.key := flight.FlightID.toString + "-actpax", props.paxComponent(flight, apiSplits)),
          <.td(^.key := flight.FlightID.toString + "-splits", splitsComponents.toTagMod))
      }.recover {
        case e => log.error(s"couldn't make flight row $e")
          <.tr(s"failure $e, ${e.getMessage} ${e.getStackTrace().mkString(",")}")
      }.get
    }

    )
    .componentWillReceiveProps(i => {
      if (i.nextProps != i.currentProps)
        log.info(s"row ${i.nextProps} changed")
      i.setState(RowState(i.nextProps != i.currentProps))
    })
    .componentDidMount(p => Callback.log(s"row didMount $p"))
    .configure(Reusability.shouldComponentUpdate)
    .build


}





