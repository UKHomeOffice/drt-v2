package drt.client.components

import drt.client.modules.FlightsWithSplitsView
import drt.shared.{AirportInfo, PaxTypeAndQueue, PaxTypesAndQueues}
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

  def timelineComponent(): js.Function = (props: js.Dynamic) => {
    logger.log.info(s"rendering timeline ${props}")
    Try {
      val rowData: mutable.Map[String, Any] = props.rowData.asInstanceOf[js.Dictionary[Any]]
      //      logger.log.info(s"rendering timeline ${rowData}")
      val sch = rowData("Sch")
      val est = rowData("Est")
      val act = rowData("Act")
      val estChox = rowData("Est Chox")
      val actChox = rowData("Act Chox")
      val times = s"sch: ${sch}, est ${est}, act ${act}, estChox $estChox, actChox ${actChox} "
      logger.log.info(s"making i tag $times")
      val i = <.i(^.`class` := "dot latest-event-dot",
        ^.style := "left: 896px;visibility: visible;opacity: 1;display: inline;transform: translateX(0px) translateZ(0px);")
      <.div(i, ^.width := "300px").render
    } match {
      case Success(s) =>
        val element: mutable.Map[String, Any] =  s.asInstanceOf[js.Dictionary[Any]]
        logger.log.info(s"got success with ${element}")
        s
      case f =>
        <.span(f.toString).render
    }
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
        logger.log.info(s"got paxandorigin")
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
        "Sch" -> makeDTReadable(f.SchDT),
        "Est" -> makeDTReadable(f.EstDT),
        "Act" -> makeDTReadable(f.ActDT),
        "Est Chox" -> makeDTReadable(f.EstChoxDT),
        "Act Chox" -> makeDTReadable(f.ActChoxDT),
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
        new GriddleComponentWrapper.ColumnMeta("Timeline", customComponent = timelineComponent()),
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
