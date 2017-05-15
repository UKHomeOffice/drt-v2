package drt.client.components

import drt.client.modules.FlightsWithSplitsView
import drt.shared._
import diode.data.{Pot, Ready}
import japgolly.scalajs.react.{ReactComponentB, _}
import japgolly.scalajs.react.vdom.all.{ReactAttr => _, TagMod => _, _react_attrString => _, _react_autoRender => _, _react_fragReactNode => _}
import japgolly.scalajs.react.vdom.prefix_<^._
import drt.client.logger
import drt.client.modules.{GriddleComponentWrapper, ViewTools}
import drt.client.services.JSDateConversions.SDate
import drt.shared.FlightsApi.FlightsWithSplits

import scala.collection.mutable
import scala.scalajs.js
import scala.scalajs.js.Dictionary
import scala.scalajs.js.annotation.{JSExportAll, ScalaJSDefined}
import scala.util.{Success, Try}

object FlightsWithSplitsTable {

  type Props = FlightsWithSplitsView.Props

  def originComponent(originMapper: (String) => (String)): js.Function = (props: js.Dynamic) => {
    val mod: TagMod = ^.title := originMapper(props.data.toString())
    <.span(props.data.toString(), mod).render
  }

  def dateTimeComponent(): js.Function = (props: js.Dynamic) => {
    val dt = props.data.toString()
    if (dt != "") {
      val sdate = SDate.parse(dt)
      val formatted = f"${sdate.getHours}%02d:${sdate.getMinutes}%02d"
      val mod: TagMod = ^.title := sdate.toLocalDateTimeString()
      <.span(formatted, mod).render
    } else {
      <.div.render
    }
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

    val re: ReactElement = Try {
      val rowData: mutable.Map[String, Any] = props.rowData.asInstanceOf[Dictionary[Any]]
      val sch: String = props.rowData.Sch.toString
      val est = rowData("Est")
      val act: String = rowData("Act").toString
      val estChox = rowData("Est Chox").toString
      val actChox = rowData("Act Chox").toString

      val (actDeltaTooltip: String, actPct: Double, actClass: String) = pctAndClass(sch, act, schPct)
      val (actChoxToolTip: String, actChoxPct: Double, actChoxClass: String) = pctAndClass(sch, actChox, schPct)


      val longToolTip =
        s"""Sch: ${dateStringAsLocalDisplay(sch)}
           |Act: ${dateStringAsLocalDisplay(act)} $actDeltaTooltip
           |ActChox: ${dateStringAsLocalDisplay(actChox)} $actChoxToolTip
        """.stripMargin

      val actChoxDot = if (!actChox.isEmpty)
        <.i(^.className := "dot act-chox-dot " + actChoxClass,
          ^.title := s"ActChox: $actChox $actChoxToolTip",
          ^.left := s"${actChoxPct}px")
      else <.span()

      val actWidth = (actChoxPct + 24) - actPct

      val schDot = <.i(^.className := "dot sch-dot",
        ^.title := s"Scheduled\n$longToolTip", ^.left := s"${schPct}px")
      val actDot = if (!act.isEmpty) <.i(^.className := "dot act-dot " + actClass,
        ^.title := s"Actual: ${dateStringAsLocalDisplay(act)}",
        ^.width := s"${actWidth}px",
        ^.left := s"${actPct}px")
      else <.span()

      val dots = schDot :: actDot :: actChoxDot :: Nil

      <.div(schDot, actDot, actChoxDot, ^.className := "timeline-container", ^.title := longToolTip )
    } match {
      case Success(s) =>
       s.render
      case f =>
        <.span(f.toString).render
    }
    re
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

  def paxComponent(): js.Function = (props: js.Dynamic) => {
    def widthStyle(width: Int) = js.Dictionary("width" -> s"$width%").asInstanceOf[js.Object]

    val paxRegex = "([0-9]+)(.)".r
    val paxAndOrigin = props.data match {
      case po: PaxAndOrigin =>
        val className: TagMod = ^.className := s"pax-${po.origin}"
        val title: TagMod = ^.title := s"from ${po.origin}"
        val relativePax = Math.floor(100 * (po.pax.toDouble / 853)).toInt
        val style = widthStyle(relativePax)
        //        logger.log.info(s"got paxandorigin")
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
    val uniqueArrivals: List[(ApiFlightWithSplits, Set[ApiFlight])] = CodeShares.uniqueArrivalsWithSplitsAndCodeshares(flights.flights)

    uniqueArrivals.map {
      case (flightAndSplit, codeShares) =>
        val f = flightAndSplit.apiFlight
        val literal = js.Dynamic.literal
        val splitsTuples: Map[PaxTypeAndQueue, Int] = flightAndSplit.splits
          .splits.groupBy(split => PaxTypeAndQueue(split.passengerType, split.queueType)
        ).map(x => (x._1, x._2.map(_.paxCount).sum))

        def splitsValues = {
          Seq(
            splitsTuples.getOrElse(PaxTypesAndQueues.eeaMachineReadableToEGate, 0),
            splitsTuples.getOrElse(PaxTypesAndQueues.eeaMachineReadableToDesk, 0),
            splitsTuples.getOrElse(PaxTypesAndQueues.eeaNonMachineReadableToDesk, 0),
            splitsTuples.getOrElse(PaxTypesAndQueues.visaNationalToDesk, 0),
            splitsTuples.getOrElse(PaxTypesAndQueues.nonVisaNationalToDesk, 0)
          )
        }

        val allCodes = f.IATA :: codeShares.map(_.IATA).toList
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
          "Flight" -> allCodes.mkString(" / "),
          "Origin" -> f.Origin)
    }
  }


  val component = ReactComponentB[Props]("FlightsWithSplitsTable")
    .render_P(props => {
      logger.log.debug(s"rendering flightstable")

      val portMapper: Map[String, Pot[AirportInfo]] = props.airportInfoProxy

      def mappings(port: String): String = {
        val res: Option[Pot[String]] = portMapper.get(port).map { info =>
          info.map(i => s"${i.airportName}, ${i.city}, ${i.country}")
        }
        res match {
          case Some(Ready(v)) => v
          case _ => "waiting for info..."
        }
      }

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
          val rows = flights.toJsArray
          GriddleComponentWrapper(results = rows,
            columnMeta = columnMeta,
            initialSort = "Sch",
            columns = props.activeCols)()
        })
      )
    }).build

  def apply(props: Props) = component(props)
}
