package spatutorial.client.components

import diode.data.Pot
import diode.react.{ModelProxy, ReactConnectProxy}
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.modules.FlightsView
import spatutorial.client.services.DeskRecTimeslot
import spatutorial.shared.FlightsApi.{Flights, QueueName}
import spatutorial.shared._

import scala.scalajs.js
import scala.scalajs.js.Date

case class PopoverWrapper(
                           position: String = "right",
                           className: String = "flights-popover",
                           trigger: String
                         ) {
  def toJS = {
    js.Dynamic.literal(
      position = position,
      className = className,
      trigger = trigger
    )
  }

  def apply(children: ReactNode*) = {
    val f = React.asInstanceOf[js.Dynamic].createFactory(js.Dynamic.global.Bundle.popover.Popover) // access real js component , make sure you wrap with createFactory (this is needed from 0.13 onwards)
    f(toJS, children.toJsArray).asInstanceOf[ReactComponentU_]
  }

}

object DeskRecsTable {
  // shorthand for styles
  @inline private def bss = GlobalStyles.bootstrapStyles

  case class UserDeskRecsRow(time: Long, crunchDeskRec: Int, userDeskRec: DeskRecTimeslot, waitTimeWithCrunchDeskRec: Int, waitTimeWithUserDeskRec: Int)

  case class Props(
                    queueName: String,
                    terminalName: String,
                    userDeskRecsRos: Seq[UserDeskRecsRow],
                    flightsPotRCP: ReactConnectProxy[Pot[Flights]],
                    airportConfig: AirportConfig,
                    airportInfoPotsRCP: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
                    stateChange: DeskRecTimeslot => Callback
                  )

  case class HoverPopoverState(hovered: Boolean = false)

  def deskUnitLabel(queueName: QueueName): String = {
    queueName match {
      case "eGate" => "Banks"
      case _ => "Desks"
    }
  }

  def HoverPopover(trigger: String,
                   flightsPotRCP: ReactConnectProxy[Pot[Flights]],
                   airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
                   time: Long
                  ) = ReactComponentB[Unit]("HoverPopover")
    .initialState_P((p) =>
      HoverPopoverState()
    ).renderS((scope, state) => {

    flightsPotRCP((flightsPotMP: ModelProxy[Pot[Flights]]) => {
      <.div(
        flightsPotMP().renderReady((allFlights: Flights) => {
          val windowSize = 60000 * 15
          val matchingFlights = flightsPotMP().map(flights => flights.copy(flights = allFlights.flights.filter(f => time <= f.PcpTime && f.PcpTime <= (time + windowSize))))
          <.div(
            ^.onMouseEnter ==> ((e: ReactEvent) => scope.modState(s => s.copy(hovered = true))),
            ^.onMouseLeave ==> ((e: ReactEvent) => scope.modState(_.copy(hovered = false))),
            if (state.hovered) {
              PopoverWrapper(trigger = trigger)(
                airportInfos(airportInfo =>
                  FlightsTable(FlightsView.Props(matchingFlights, airportInfo.value, List(
                    "SchDT",
                    "IATA",
                    "Origin",
                    "MaxPax",
                    "ActPax"
                  )))))
            } else {
              trigger
            }
          )
        }),
        flightsPotMP().renderEmpty(trigger)
      )
    })

  }).build

  private val component = ReactComponentB[Props]("DeskRecsTable")
    .render_P(p => {
      val style = bss.listGroup
      def renderItem(itemWithIndex: (UserDeskRecsRow, Int)) = {
        val item = itemWithIndex._1
        val date: Date = new Date(item.time)
        val formattedDate = jsDateFormat.formatDate(date)
        val airportInfo: ReactConnectProxy[Map[String, Pot[AirportInfo]]] = p.airportInfoPotsRCP
        val popover = HoverPopover(formattedDate, p.flightsPotRCP, airportInfo, item.time)
        val hasChangeClasses = if (item.userDeskRec.deskRec != item.crunchDeskRec) "table-info" else ""
        val warningClasses = if (item.waitTimeWithCrunchDeskRec < item.waitTimeWithUserDeskRec) "table-warning" else ""
        val dangerWait = if (item.waitTimeWithUserDeskRec > p.airportConfig.slaByQueue(p.queueName)) "table-danger"
        <.tr(^.key := item.time,
          ^.cls := warningClasses,
          <.td(^.cls := "date-field", popover()),
          <.td(item.crunchDeskRec),
          <.td(
            ^.cls := hasChangeClasses,
            <.input.number(
              ^.className := "desk-rec-input",
              ^.value := item.userDeskRec.deskRec,
              ^.onChange ==> ((e: ReactEventI) => p.stateChange(DeskRecTimeslot(item.userDeskRec.id, deskRec = e.target.value.toInt))))),
          <.td(^.cls := "minutes", item.waitTimeWithCrunchDeskRec + " mins"),
          <.td(^.cls := dangerWait + " " + warningClasses + " minutes", item.waitTimeWithUserDeskRec + " mins")
        )
      }
      <.table(^.cls := "table table-striped table-hover table-sm",
        <.tbody(
          <.tr(<.th(""), <.th(deskUnitLabel(p.queueName), ^.colSpan := 2), <.th("Wait Times", ^.colSpan := 2)),
          <.tr(<.th("Time"), <.th("Required"), <.th("Available"), <.th("With Reqs"), <.th("With Available")),
          p.userDeskRecsRos.zipWithIndex map renderItem))
    })
    .build


  def apply(queueName: String, terminalName: String, userDeskRecRows: Seq[UserDeskRecsRow], flightsPotRCP: ReactConnectProxy[Pot[Flights]],
            airportConfig: AirportConfig,
            airportInfoPotsRCP: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
            stateChange: DeskRecTimeslot => Callback) =
    component(Props(queueName, terminalName, userDeskRecRows, flightsPotRCP, airportConfig, airportInfoPotsRCP, stateChange))
}
