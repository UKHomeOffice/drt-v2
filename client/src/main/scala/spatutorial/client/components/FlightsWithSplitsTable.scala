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


object FlightTableComponents {

  def airportCodeComponent(portMapper: Map[String, Pot[AirportInfo]])(port: String): VdomElement = {
    val tt = airportCodeTooltipText(portMapper) _
    <.span(^.title := tt(port), port)
  }

  def airportCodeComponentLensed(portInfoPot: Pot[AirportInfo])(port: String): VdomElement = {
    val tt: Option[Pot[String]] = Option(potAirportInfoToTooltip(portInfoPot))
    log.info(s"making airport info $port $tt")
    <.span(^.title := airportInfoDefault(tt), port)
  }

  def airportCodeTooltipText(portMapper: Map[String, Pot[AirportInfo]])(port: String): String = {
    val portInfoOptPot = portMapper.get(port)

    val res: Option[Pot[String]] = portInfoOptPot.map {
      potAirportInfoToTooltip
    }
    airportInfoDefault(res)
  }

  private def airportInfoDefault(res: Option[Pot[String]]): String = {
    log.info(s"airportInfoDefault got one! $res")
    res match {
      case Some(Ready(v)) => v
      case _ => "waiting for info..."
    }
  }

  private def potAirportInfoToTooltip(info: Pot[AirportInfo]): Pot[String] = {
    info.map(i => s"${i.airportName}, ${i.city}, ${i.country}")
  }

  def originComponent(originMapper: (String) => (String)): js.Function = (props: js.Dynamic) => {
    val mod: TagMod = ^.title := originMapper(props.data.toString())
    <.span(props.data.toString(), mod).render
  }

  def dateTimeComponent(): js.Function = (props: js.Dynamic) => {
    val dt = props.data.toString()
    if (dt != "") {
      localDateTimeWithPopup(dt)
    } else {
      <.div.render
    }
  }

  def localDateTimeWithPopup(dt: String) = {
    if (dt.isEmpty) <.span() else localTimePopup(dt)
  }

  private def localTimePopup(dt: String) = {
    val sdate = SDate.parse(dt)
    val hhmm = f"${sdate.getHours}%02d:${sdate.getMinutes}%02d"
    val titlePopup: TagMod = ^.title := sdate.toLocalDateTimeString()
    <.span(hhmm, titlePopup).render
  }

  def millisDelta(time1: String, time2: String) = {
    SDate.parse(time1).millisSinceEpoch - SDate.parse(time2).millisSinceEpoch
  }


  def asOffset(delta: Long, range: Double) = {
    val aggression = 1.0019
    val deltaTranslate = 1700
    val scaledDelta = 1.0 * delta / 1000
    val isLate = delta < 0
    if (isLate) {
      (range / (1 + Math.pow(aggression, (scaledDelta + deltaTranslate))))
    }
    else {
      -(range / (1 + Math.pow(aggression, -1.0 * (scaledDelta - deltaTranslate))))
    }
  }

  def dateStringAsLocalDisplay(dt: String) = dt match {
    case "" => ""
    case some => SDate.parse(dt).toLocalDateTimeString()
  }

  def timelineCompFunc(flight: ApiFlight): VdomElement = {
    Try {
      timelineFunc(150 - 24, flight.SchDT, flight.ActDT, flight.ActChoxDT)
    }.recover {
      case e =>
        log.error(s"couldn't render timeline of $flight with $e")
        val recovery: VdomElement = <.div("uhoh!")
        recovery
    }.get
  }

  def timelineFunc(schPct: Int, sch: String, act: String, actChox: String): VdomElement = {
    val (actDeltaTooltip: String, actPct: Double, actClass: String) = pctAndClass(sch, act, schPct)
    val (actChoxToolTip: String, actChoxPct: Double, actChoxClass: String) = pctAndClass(sch, actChox, schPct)


    val longToolTip =
      s"""Sch: ${dateStringAsLocalDisplay(sch)}
         |Act: ${dateStringAsLocalDisplay(act)} $actDeltaTooltip
         |ActChox: ${dateStringAsLocalDisplay(actChox)} $actChoxToolTip
        """.stripMargin

    val actChoxDot = if (!actChox.isEmpty)
      <.i(^.className :=
        "dot act-chox-dot " + actChoxClass,
        ^.title := s"ActChox: $actChox $actChoxToolTip",
        ^.left := s"${actChoxPct}px")
    else <.span()


    val actWidth = (actChoxPct + 24) - actPct

    val schDot = <.i(^.className := "dot sch-dot",
      ^.title := s"Scheduled\n$longToolTip", ^.left := s"${schPct}px")
    val
    actDot = if (!act.isEmpty) <.i(^.className := "dot act-dot "
      + actClass,
      ^.title
        := s"Actual: ${dateStringAsLocalDisplay(act)}",
      ^.width := s"${actWidth}px",
      ^.left := s"${actPct}px")
    else <.span()

    val dots = schDot :: actDot ::
      actChoxDot :: Nil

    <.div(schDot, actDot, actChoxDot, ^.className := "timeline-container", ^.title := longToolTip)

  }

  private def pctAndClass(sch: String, act: String, schPct: Int) = {
    val actDelta = millisDelta(sch, act)
    val actDeltaTooltip = {
      val dm = (actDelta / 60000)
      Math.abs(dm) + s"mins ${deltaMessage(actDelta)}"
    }
    val actPct = schPct + asOffset(actDelta, 150.0)
    val actClass: String = deltaMessage(actDelta)
    (actDeltaTooltip, actPct, actClass)
  }

  def deltaMessage(actDelta: Long) = {
    val actClass = actDelta match {
      case d if d < 0 => "late"
      case d if d == 0 => "on-time"
      case d if d > 0 => "early"
    }
    actClass
  }

  val uniqueArrivalsWithCodeShares = CodeShares.uniqueArrivalsWithCodeshares((f: ApiFlightWithSplits) => identity(f.apiFlight)) _
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

        val splitsComponents = flightSplitsList map {
          flightSplits => {
            val splitStyle = flightSplits.splitStyle
            val vdomElement: VdomElement = splitStyle match {
              case PaxNumbers => {
                val splitTotal = flightSplits.splits.map(_.paxCount.toInt).sum
                val splitStyleUnitLabe = "pax"
                val queuePax: Map[PaxTypeAndQueue, Int] = flightSplits.splits.map({
                  case s if splitStyle == PaxNumbers => PaxTypeAndQueue(s.passengerType, s.queueType) -> s.paxCount.toInt
                }).toMap
                val orderedSplitCounts: Seq[(PaxTypeAndQueue, Int)] = PaxTypesAndQueues.inOrder.map(ptq => ptq -> queuePax.getOrElse(ptq, 0))
                val splitsAndLabels: Seq[(String, Int)] = orderedSplitCounts.map {
                  case (ptqc, paxCount) => (s"$splitStyleUnitLabe ${ptqc.passengerType} > ${ptqc.queueType}", paxCount)
                }
                <.div(props.splitsGraphComponent(splitTotal, splitsAndLabels), flightSplits.source)
              }
              case Percentage => {
                val splitTotal = flightSplits.splits.map(_.paxCount.toInt).sum
                val splitStyle = flightSplits.splitStyle
                val splitStyleUnitLabe = "%"
                val queuePax: Map[PaxTypeAndQueue, Int] = flightSplits.splits.map({
                  case s if splitStyle == Percentage => PaxTypeAndQueue(s.passengerType, s.queueType) -> s.paxCount.toInt
                }).toMap
                val orderedSplitCounts: Seq[(PaxTypeAndQueue, Int)] = PaxTypesAndQueues.inOrder.map(ptq => ptq -> queuePax.getOrElse(ptq, 0))
                val splitsAndLabels: Seq[(String, Int)] = orderedSplitCounts.map {
                  case (ptqc, paxCount) => (s"$splitStyleUnitLabe ${ptqc.passengerType} > ${ptqc.queueType}", paxCount)
                }
                <.div(props.splitsGraphComponent(splitTotal, splitsAndLabels), <.span(^.className := "flightSplitSource", flightSplits.source))
              }
            }
            vdomElement
          }
        }


        val hasChangedStyle = if (state.hasChanged) ^.background := "rgba(255, 200, 200, 0.5) " else ^.outline := ""
        val apiSplits = flightWithSplits.splits.find(splits => splits.source == SplitRatiosNs.SplitSources.AdvPaxInfo).getOrElse(ApiSplits(Nil, "no splits - client"))
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