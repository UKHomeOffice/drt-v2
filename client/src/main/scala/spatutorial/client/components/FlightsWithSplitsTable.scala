package drt.client.components

import diode.data.{Pot, Ready}
import japgolly.scalajs.react.ReactComponentB
import japgolly.scalajs.react.vdom.prefix_<^.{<, TagMod, ^}
import drt.client.modules.{FlightsWithSplitsView, GriddleComponentWrapper, ViewTools}
import drt.shared.{AirportInfo, PaxTypeAndQueue, PaxTypesAndQueues}
import drt.client.modules.{FlightsWithSplitsView, GriddleComponentWrapper, ViewTools}
import drt.shared.{AirportInfo, PaxTypeAndQueue, PaxTypesAndQueues}
import chandu0101.scalajs.react.components.Spinner
import diode.data.{Pot, Ready}
import japgolly.scalajs.react.{ReactComponentB, _}
import japgolly.scalajs.react.vdom.all.{ReactAttr => _, TagMod => _, _react_attrString => _, _react_autoRender => _, _react_fragReactNode => _}
import japgolly.scalajs.react.vdom.prefix_<^._
import drt.client.modules.{GriddleComponentWrapper, ViewTools}
import drt.client.logger
import drt.client.modules.{GriddleComponentWrapper, ViewTools}
import drt.client.services.JSDateConversions.SDate
import drt.shared.FlightsApi.FlightsWithSplits

import scala.scalajs.js
import scala.scalajs.js.Object

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

  def paxComponent(): js.Function = (props: js.Dynamic) => {
    val paxRegex = "([0-9]+)(.)".r
    val paxAndType: (String, String) = props.data.toString() match {
      case paxRegex(p, t) =>
        val style = if (t == "A") "api"
        else if (t == "B") "port"
        else "unknown"
        (p, style)
      case _ => ("n/a", "unknown")
    }
    val className: TagMod = ^.className := s"pax-${paxAndType._2}"
    val title: TagMod = ^.title := s"from ${paxAndType._2}"
    <.div(paxAndType._1, className, title).render
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
        .splits.groupBy(split => {
        PaxTypeAndQueue(split.passengerType, split.queueType)
      }
      ).map(x => (x._1, x._2.map(_.paxCount).sum))
      logger.log.debug("flightAndSplit:" + splitsTuples)

      import drt.shared.DeskAndPaxTypeCombinations._

      val total = "API total"

      def splitsField(fieldName: String, ptQ: PaxTypeAndQueue): (String, scalajs.js.Any) = {
        fieldName -> (splitsTuples.get(ptQ) match {
          case Some(v: Int) => Int.box(v)
          case None => ""
        })
      }

      literal(
        splitsField(deskEeaNonMachineReadable, PaxTypesAndQueues.eeaNonMachineReadableToDesk),
        splitsField(nationalsDeskVisa, PaxTypesAndQueues.visaNationalToDesk),
        splitsField(nationalsDeskNonVisa, PaxTypesAndQueues.nonVisaNationalToDesk),
        splitsField(egate, PaxTypesAndQueues.eeaMachineReadableToEGate),
        splitsField(deskEea, PaxTypesAndQueues.eeaMachineReadableToDesk),
//        total -> splitsTuples.values.sum,
        //        "Operator" -> f.Operator,
        "Status" -> f.Status,
        "Sch" -> makeDTReadable(f.SchDT),
        "Est" -> makeDTReadable(f.EstDT),
        "Act" -> makeDTReadable(f.ActDT),
        "Est chox" -> makeDTReadable(f.EstChoxDT),
        "Act chox" -> makeDTReadable(f.ActChoxDT),
        "Gate" -> f.Gate,
        "Stand" -> f.Stand,
        "Pax" -> paxDisplay(f.MaxPax, f.ActPax, splitsTuples.values.sum),
//        "MaxPax" -> f.MaxPax,
//        "ActPax" -> f.ActPax,
        "TranPax" -> f.TranPax,
//        "RunwayID" -> f.RunwayID,
//        "BaggageReclaimId" -> f.BaggageReclaimId,
//        "FlightID" -> f.FlightID,
//        "AirportID" -> f.AirportID,
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
        new GriddleComponentWrapper.ColumnMeta("Origin", customComponent = originComponent(mappings)),
        new GriddleComponentWrapper.ColumnMeta("Sch", customComponent = dateTimeComponent()),
        new GriddleComponentWrapper.ColumnMeta("Est", customComponent = dateTimeComponent()),
        new GriddleComponentWrapper.ColumnMeta("Act", customComponent = dateTimeComponent()),
        new GriddleComponentWrapper.ColumnMeta("Act chox", customComponent = dateTimeComponent()),
        new GriddleComponentWrapper.ColumnMeta("Pax", customComponent = paxComponent())
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
