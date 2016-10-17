package spatutorial.client.components

import diode.data.Pot
import diode.react._
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.ReactTagOf
import japgolly.scalajs.react.vdom.prefix_<^._
import org.scalajs.dom.html.{TableCell, TableHeaderCell}
import spatutorial.client.modules.FlightsView
import spatutorial.client.services.{DeskRecTimeslot, UserDeskRecs}
import spatutorial.shared.{AirportInfo, SimulationResult}
import spatutorial.shared.FlightsApi.{Flights, QueueName, TerminalName}

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

  case class QueueDetailsRow(crunchDeskRec: Int,
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
            (FlightsTable(FlightsView.Props(matchingFlights, airportInfo.value)))))
      } else {
        trigger
      })
    popover
  }).build

  private val TodoList = ReactComponentB[Props]("TodoList")
    .render_P(p => {
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
          q => Seq(<.td(q.crunchDeskRec), <.td(q.userDeskRec.deskRec),
            <.td(q.waitTimeWithCrunchDeskRec), <.td(q.waitTimeWithUserDeskRec))).toList
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
      val queueNames = "EEA" :: "NON-EEA" :: "E-GATES" :: Nil
      val flatten: List[TagMod] = List.fill(3)(List(<.th("Desks", ^.colSpan := 2), <.th("Wait Times", ^.colSpan := 2))).flatten
      val fill: List[TagMod] = List.fill(3)(List(<.th("Recommended Desks"), <.th("Your Desks"), <.th("With Yours"), <.th("With Recommended"))).flatten
      <.table(^.cls := "table table-striped table-hover table-sm",
        <.tbody(
          <.tr(<.th("") :: queueNames.map(queueName => <.th(<.h2(queueName), ^.colSpan := 4)): _*),
          <.tr(<.th("") :: flatten: _*),
          <.tr(<.th("Time") :: fill: _*),
          p.items.zipWithIndex map renderItem))
    })
    .build

  def apply(items: Seq[TerminalUserDeskRecsRow], flights: Pot[Flights],
            airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
            stateChange: DeskRecTimeslot => Callback) =
    TodoList(Props(items, flights, airportInfos, stateChange))
}

