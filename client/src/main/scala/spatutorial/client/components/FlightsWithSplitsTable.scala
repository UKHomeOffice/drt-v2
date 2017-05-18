package drt.client.components

import drt.client.modules.FlightsWithSplitsView
import drt.shared._
import diode.data.{Pot, Ready}
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import drt.client.logger
import drt.client.modules.{GriddleComponentWrapper, ViewTools}
import drt.client.services.JSDateConversions.SDate
import drt.shared.FlightsApi.FlightsWithSplits

import scala.collection.mutable
import scala.scalajs.js
import scala.scalajs.js.Dictionary
import scala.scalajs.js.annotation.{JSExportAll, ScalaJSDefined}
import scala.util.{Failure, Success, Try}
import logger._

import scala.collection.JavaConverters._

object FlightsWithSplitsTable {

  type Props = FlightsWithSplitsView.Props

  def ArrivalsTable[C](timelineComponent: Option[(ApiFlight) => VdomNode] = None,
                       originMapper: (String) => VdomNode = (portCode) => portCode,
                       paxComponent: (ApiFlight) => TagMod = (flight) => flight.ActPax
                      ) = ScalaComponent.builder[FlightsWithSplits]("ArrivalsTable")
    .renderP((_$, flightsWithSplits) => {
      log.info(s"sorting flights")
      val flights = flightsWithSplits.flights
      val sortedFlights = flights.sortBy(_.apiFlight.SchDT) //todo move this closer to the model
      log.info(s"sorted flights")
      val isTimeLineSupplied = timelineComponent.isDefined
      val timelineTh = (if (isTimeLineSupplied) <.th("Timeline") :: Nil else List[TagMod]()).toTagMod
      Try {

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
              <.th("Pax"),
              <.th("Splits")
            )),
            <.tbody(
              sortedFlights.zipWithIndex.map {
                case (flightWithSplits, idx) => {
                  val flight = flightWithSplits.apiFlight
                  log.info(s"rendering flight row $idx ${flight.toString}")
                  Try {
                    <.tr(^.key := flight.FlightID.toString,
                      timelineComponent.map(timeline => <.td(timeline(flight))).toList.toTagMod,
                      <.td(^.key := flight.FlightID.toString + "-flightNo", flight.ICAO),
                      <.td(^.key := flight.FlightID.toString + "-origin", originMapper(flight.Origin)),
                      <.td(^.key := flight.FlightID.toString + "-gatestand", s"${flight.Gate}/${flight.Stand}"),
                      <.td(^.key := flight.FlightID.toString + "-status", flight.Status),
                      <.td(^.key := flight.FlightID.toString + "-schdt", localDateTimeWithPopup(flight.SchDT)),
                      <.td(^.key := flight.FlightID.toString + "-estdt", localDateTimeWithPopup(flight.EstDT)),
                      <.td(^.key := flight.FlightID.toString + "-actdt", localDateTimeWithPopup(flight.ActDT)),
                      <.td(^.key := flight.FlightID.toString + "-estchoxdt", localDateTimeWithPopup(flight.EstChoxDT)),
                      <.td(^.key := flight.FlightID.toString + "-actchoxdt", localDateTimeWithPopup(flight.ActChoxDT)),
                      <.td(^.key := flight.FlightID.toString + "-actpax", paxComponent(flight)),
                      <.td(^.key := flight.FlightID.toString + "-splits", flightWithSplits.splits.toString))
                  }.recover {
                    case e => log.error(s"couldn't make flight row $e")
                      <.tr(s"failure $e")
                  }.get
                }
              }.toTagMod)))
      } match {
        case Success(s) =>
          log.info(s"table rendered!!")
          s

        case Failure(f) =>
          log.error(s"filure in table render $f")
          <.div(s"render failure ${f}")
      }
    })
    .build

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

  def timelineComponent(): js.Function = (props: js.Dynamic) => {
    val schPct = 150 - 24

    val re: VdomElement = Try {
      val rowData: mutable.Map[String, Any] = props.rowData.asInstanceOf[Dictionary[Any]]
      val sch: String = props.rowData.Sch.toString
      val est = rowData("Est")
      val act: String = rowData("Act").toString
      val estChox = rowData("Est Chox").toString
      val actChox = rowData("Act Chox").toString

      timelineFunc(schPct, sch, act, actChox)
    } match {
      case Success(s) =>
        <.span(s).render
      case f =>
        <.span(f.toString).render
    }
    re
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

    val actWidth = (actChoxPct + 24) -
      actPct

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

  def widthStyle(width: Int) = js.Dictionary("width" -> s"$width%").asInstanceOf[js.Object]

  def paxComponent(): js.Function = (props: js.Dynamic) => {

    val paxRegex = "([0-9]+)(.)".r
    val paxAndOrigin = props.data match {
      case po: PaxAndOrigin =>
        val origin = po.origin
        val pax = po.pax.toDouble

        val className: TagMod = ^.className := s"pax-${origin}"
        val title: TagMod = ^.title := s"from ${origin}"
        val relativePax = Math.floor(100 * (pax / 853)).toInt
        val style = widthStyle(relativePax)
        <.div(po.pax, className, title, ^.style := style)
      case e =>
        logger.log.warn(s"Expected a PaxAndOrigin but got $e")
        <.div("unknown")
    }
    paxAndOrigin.render
  }

  def splitsComponent(): js.Function = (props: js.Dynamic) => {
    def heightStyle(height: String) = js.Dictionary("height" -> height).asInstanceOf[js.Object]

    val splitLabels = Array("eGate", "EEA", "EEA NMR", "Visa", "Non-visa")
    val splits = props.data.toString.split("\\|").map(_.toInt)
    val desc = 0 to 4 map (idx => s"${splitLabels(idx)}: ${splits(idx)}")
    val sum = splits.sum
    val pc = splits.map(s => s"${(100 * s.toDouble / sum).round}%")
    <.div(^.className := "splits", ^.title := desc.mkString("\n"),
      <.div(^.className := "graph",
        <.div(^.className := "bar", ^.style := heightStyle(pc(0))),
        <.div(^.className := "bar", ^.style := heightStyle(pc(1))),
        <.div(^.className := "bar", ^.style := heightStyle(pc(2))),
        <.div(^.className := "bar", ^.style := heightStyle(pc(3))),
        <.div(^.className := "bar", ^.style := heightStyle(pc(4)))
      )).render
  }


  @ScalaJSDefined
  class PaxAndOrigin(val pax: Int, val origin: String) extends js.Object

  object PaxAndOrigin {
    def apply(paxNos: Int, origin: String) = {
      new PaxAndOrigin(paxNos, origin)
    }
  }

  def paxOriginDisplay(max: Int, act: Int, api: Int): PaxAndOrigin = {
    if (api > 0) PaxAndOrigin(api, "api")
    else if (act > 0) PaxAndOrigin(act, "port")
    else PaxAndOrigin(max, "capacity")
  }

  def paxDisplay(max: Int, act: Int, api: Int): String = {
    if (api > 0) s"${api}A"
    else if (act > 0) s"${act}B"
    else s"${max}C"
  }

  def reactTableFlightsAsJsonDynamic(flights: FlightsWithSplits): List[js.Dynamic] = {

    flights.flights.map(flightAndSplit => {
      val f = flightAndSplit.apiFlight
      val literal = js.Dynamic.literal
      val splitsTuples: Map[PaxTypeAndQueue, Int] = flightAndSplit.splits
        .splits.groupBy(split => PaxTypeAndQueue(split.passengerType, split.queueType)
      ).map(x => (x._1, x._2.map(_.paxCount).sum))

      import drt.shared.DeskAndPaxTypeCombinations._

      val total = "API total"

      def splitsField(fieldName: String, ptQ: PaxTypeAndQueue): (String, scalajs.js.Any) = {
        fieldName -> (splitsTuples.get(ptQ) match {
          case Some(v: Int) => Int.box(v)
          case None => ""
        })
      }

      def splitsValues = {
        Seq(
          splitsTuples.getOrElse(PaxTypesAndQueues.eeaMachineReadableToEGate, 0),
          splitsTuples.getOrElse(PaxTypesAndQueues.eeaMachineReadableToDesk, 0),
          splitsTuples.getOrElse(PaxTypesAndQueues.eeaNonMachineReadableToDesk, 0),
          splitsTuples.getOrElse(PaxTypesAndQueues.visaNationalToDesk, 0),
          splitsTuples.getOrElse(PaxTypesAndQueues.nonVisaNationalToDesk, 0)
        )
      }

      literal(
        //        "Operator" -> f.Operator,
        "Timeline" -> "0",
        "Status" -> f.Status,
        "Sch" -> f.SchDT,
        "Est" -> f.EstDT,
        "Act" -> f.ActDT,
        "Est Chox" -> f.EstChoxDT,
        "Act Chox" -> f.ActChoxDT,
        "Gate" -> f.Gate,
        "Stand" -> f.Stand,
        "Pax" -> paxOriginDisplay(f.MaxPax, f.ActPax, splitsTuples.values.sum),
        "Splits" -> splitsValues.mkString("|"),
        "TranPax" -> f.TranPax,
        "Terminal" -> f.Terminal,
        "ICAO" -> f.ICAO,
        "Flight" -> f.IATA,
        "Origin" -> f.Origin)
    })
  }


  val component = ScalaComponent.builder[Props]("FlightsWithSplitsTable")
    .render_P(props => {
      logger.log.debug(s"rendering flightstable")

      val mappings = airportCodeTooltipText(props.airportInfoProxy) _

      val columnMeta = Some(Seq(
        new GriddleComponentWrapper.ColumnMeta("Timeline",
          cssClassName = "timeline-column",
          customComponent = timelineComponent()),
        new GriddleComponentWrapper.ColumnMeta("Origin", customComponent = originComponent(mappings)),
        new GriddleComponentWrapper.ColumnMeta("Sch", customComponent = dateTimeComponent()),
        new GriddleComponentWrapper.ColumnMeta("Est", customComponent = dateTimeComponent()),
        new GriddleComponentWrapper.ColumnMeta("Act", customComponent = dateTimeComponent()),
        new GriddleComponentWrapper.ColumnMeta("Est Chox", customComponent = dateTimeComponent()),
        new GriddleComponentWrapper.ColumnMeta("Act Chox", customComponent = dateTimeComponent()),
        new GriddleComponentWrapper.ColumnMeta("Pax", customComponent = paxComponent()),
        new GriddleComponentWrapper.ColumnMeta("Splits", customComponent = splitsComponent())
      ))
      <.div(^.className := "table-responsive timeslot-flight-popover",
        props.flightsModelProxy.renderPending((t) => ViewTools.spinner),
        props.flightsModelProxy.renderEmpty(ViewTools.spinner),
        props.flightsModelProxy.renderReady(flights => {
          val rows = flights
          <.table(
            <.tbody(
              rows.map(row =>
                <.tr(
                  <.td(row.SchDt.toString)
                )
              ).toTagMod
            )
          )

        })
      )
    }).build

  def apply(props: Props) = component(props)
}
