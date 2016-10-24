package spatutorial.client.components

import diode.data.Pot
import diode.react._
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.ReactTagOf
import japgolly.scalajs.react.vdom.prefix_<^._
import org.scalajs.dom.html.{Div, TableCell, TableHeaderCell}
import spatutorial.client.TableViewUtils
import spatutorial.client.logger._
import spatutorial.client.modules.FlightsView
import spatutorial.client.services._
import spatutorial.shared._
import spatutorial.shared.FlightsApi.{Flights, QueueName, TerminalName}

import scala.collection.immutable.{Map, Seq}
import scala.scalajs.js.Date

object TerminalUserDeskRecs {

  case class Props(terminalName: TerminalName,
                   workloads: Map[QueueName, Seq[Int]],
                   userDeskRecs: Map[QueueName, UserDeskRecs])

  val component = ReactComponentB[Props]("TerminalUserDeskRecs")
    .render_P(props =>
      <.table(
        <.tr(<.td())
      )
    )
}


object TableTerminalDeskRecs {
  // shorthand for styles
  @inline private def bss = GlobalStyles.bootstrapStyles

  case class QueueDetailsRow(
                              pax: Double,
                              crunchDeskRec: Int,
                              userDeskRec: DeskRecTimeslot,
                              waitTimeWithCrunchDeskRec: Int,
                              waitTimeWithUserDeskRec: Int)

  case class TerminalUserDeskRecsRow(time: Long, queueDetails: Seq[QueueDetailsRow])


  case class Props(
                    items: Seq[TerminalUserDeskRecsRow],
                    flights: Pot[Flights],
                    airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
                    stateChange: DeskRecTimeslot => Callback
                  )

  case class HoverPopoverState(hovered: Boolean = false)

  def HoverPopover(trigger: String,
                   matchingFlights: Pot[Flights],
                   airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]]) = ReactComponentB[Unit]("HoverPopover")
    .initialState_P((p) =>
      HoverPopoverState()
    ).renderS((scope, state) => {
    val popover = <.div(
      ^.onMouseEnter ==> ((e: ReactEvent) => scope.modState(s => s.copy(hovered = true))),
      ^.onMouseLeave ==> ((e: ReactEvent) => scope.modState(_.copy(hovered = false))),
      if (state.hovered) {
        PopoverWrapper(trigger = trigger)(
          airportInfos(airportInfo =>
            FlightsTable(FlightsView.Props(matchingFlights, airportInfo.value))))
      } else {
        trigger
      })
    popover
  }).build

  def buildTerminalUserDeskRecsComponent = {
    val airportWrapper = SPACircuit.connect(_.airportInfos)
    val flightsWrapper = SPACircuit.connect(m => m.flights)
    val simulationResultWrapper = SPACircuit.connect(_.simulationResult)
    val workloadWrapper = SPACircuit.connect(_.workload)
    val queueCrunchResultsWrapper = SPACircuit.connect(_.queueCrunchResults)
    val userDeskRecsWrapper = SPACircuit.connect(_.userDeskRec)

    flightsWrapper(flightsProxy => {
      queueCrunchResultsWrapper(crunchResultsMP => {
        simulationResultWrapper(simulationResultMP => {
          workloadWrapper((workloadsMP: ModelProxy[Pot[Workloads]]) => {
            userDeskRecsWrapper(userDeskRecsMP => {
              <.div(
                <.h1("A1 Desks"),
                workloadsMP().renderReady((workloads: Workloads) => {
                  val crv = crunchResultsMP.value.getOrElse("A1", Map())
                  val srv = simulationResultMP.value.getOrElse("A1", Map())
                  val paxloads: Map[String, List[Double]] = WorkloadsHelpers.paxloadsByQueue(workloadsMP.value.get.workloads("A1"))
                  val rows = TableViewUtils.terminalUserDeskRecsRows(paxloads, crv, srv)
                  <.div(
                    TableTerminalDeskRecs(
                      rows,
                      flightsProxy.value,
                      airportWrapper,
                      deskRecTimeslot => {
                        log.info(s"updating user desk for $deskRecTimeslot")
                        userDeskRecsMP.dispatch(UpdateDeskRecsTime("A1", "eeaDesk", deskRecTimeslot))
                      }
                    ))
                }),
                workloadsMP().renderPending(_ => <.div("Waiting for crunch results"))
              )
            })
          })
        })
      })
    })
  }

  class Backend($: BackendScope[Props, Unit]) {

    def render(p: Props) = {
      log.info("%%%%%%%rendering table...")
      val style = bss.listGroup
      def renderItem(itemWithIndex: (TerminalUserDeskRecsRow, Int)) = {
        val item = itemWithIndex._1
        val time = item.time
        val windowSize = 60000 * 15
        val flights: Pot[Flights] = p.flights.map(flights =>
          flights.copy(flights = flights.flights.filter(f => time <= f.PcpTime && f.PcpTime <= (time + windowSize))))
        val date: Date = new Date(item.time)
        val trigger: String = date.toLocaleDateString() + " " + date.toLocaleTimeString().replaceAll(":00$", "")
        val airportInfo: ReactConnectProxy[Map[String, Pot[AirportInfo]]] = p.airportInfos
        val popover = HoverPopover(trigger, flights, airportInfo)
        val fill = item.queueDetails.flatMap(
          (q: QueueDetailsRow) => Seq(
            <.td(q.pax),
            <.td(q.crunchDeskRec),
            <.td(
              <.input.number(
                ^.value := q.userDeskRec.deskRec,
                ^.onChange ==> ((e: ReactEventI) => p.stateChange(DeskRecTimeslot(q.userDeskRec.id, deskRec = e.target.value.toInt)))
              )),
            <.td(q.waitTimeWithCrunchDeskRec),
            <.td(q.waitTimeWithUserDeskRec))
        ).toList
        <.tr(<.td(item.time) :: fill: _*)
        //        val hasChangeClasses = if (item.userDeskRec.deskRec != item.crunchDeskRec) "table-info" else ""
        //        val warningClasses = if (item.waitTimeWithCrunchDeskRec < item.waitTimeWithUserDeskRec) "table-warning" else ""
        //        val dangerWait = if (item.waitTimeWithUserDeskRec > 25) "table-danger"
        //        <.tr(^.key := item.time,
        //          ^.cls := warningClasses,
        //          <.td(popover()),
        //          <.td(item.crunchDeskRec),
        //          <.td(
        //            ^.cls := hasChangeClasses,
        //            <.input.number(
        //              ^.className := "desk-rec-input",
        //              ^.value := item.userDeskRec.deskRec,
        //              ^.onChange ==> ((e: ReactEventI) => p.stateChange(DeskRecTimeslot(item.userDeskRec.id, deskRec = e.target.value.toInt))))),
        //          <.td(^.cls := dangerWait + " " + warningClasses, item.waitTimeWithUserDeskRec),
        //          <.td(item.waitTimeWithCrunchDeskRec)
        //        )
      }
      val queueNames = WorkloadsHelpers.queueNames.values.toList
      val flatten: List[TagMod] = List.fill(3)(List(<.th(""), <.th("Desks", ^.colSpan := 2), <.th("Wait Times", ^.colSpan := 2))).flatten
      val fill: List[TagMod] = List.fill(3)(List(<.th("Pax"), <.th("Rec Desks"), <.th("Your Desks"), <.th("With Yours"), <.th("With Recs"))).flatten
      <.table(^.cls := "table table-striped table-hover table-sm",
        <.tbody(
          <.tr(<.th("") :: queueNames.map(queueName => <.th(<.h2(queueName), ^.colSpan := 5)): _*),
          <.tr(<.th("") :: flatten: _*),
          <.tr(<.th("Time") :: fill: _*),
          p.items.zipWithIndex map renderItem))
    }
  }

  private val component = ReactComponentB[Props]("TerminalUserDeskRecs")
    .renderBackend[Backend]
    .build

  def apply(items: Seq[TerminalUserDeskRecsRow], flights: Pot[Flights],
            airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
            stateChange: DeskRecTimeslot => Callback) =
    component(Props(items, flights, airportInfos, stateChange))
}

