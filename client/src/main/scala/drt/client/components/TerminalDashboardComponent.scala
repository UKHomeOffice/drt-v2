package drt.client.components

import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.shared.FlightsApi.TerminalName
import drt.shared.{AirportConfig, PortState, PortStateLike, Queues, SDateLike}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ScalaComponent}


object TerminalDashboardComponent {

  case class Props(terminal: TerminalName, airportConfig: AirportConfig, portState: PortState)

  val slotSize = 15

  def timeSlotStart: SDateLike => SDateLike = timeSlotForTime(slotSize)

  val component = ScalaComponent.builder[Props]("TerminalDashboard")
    .render_P(p => {
      val start = timeSlotStart(SDate.now())
      val ps = p.portState.window(start, start.addMinutes(slotSize), p.airportConfig.queues)
      val prevSlotPortState: PortStateLike = p.portState.window(start.addMinutes(-slotSize), start, p.airportConfig.queues)

      val terminalPax = ps.crunchMinutes.map {
        _._2.paxLoad
      }.sum.round

      <.div(^.className := "terminal-dashboard",
        <.div(^.className := "pax-bar", s"$terminalPax passengers presenting at the PCP"),

        p.airportConfig.queues.getOrElse(p.terminal, List()).map((q: String) => {

          val qCMs = ps.crunchMinutes.collect { case (tqm, cm) if tqm.queueName == q => cm }
          val prevSlotCMs = prevSlotPortState.crunchMinutes.collect { case (tqm, cm) if tqm.queueName == q => cm }
          val qPax = qCMs.map(_.paxLoad).sum.round
          val qWait = qCMs.map(cm => cm.deployedWait.getOrElse(cm.waitTime)).max
          val prevSlotQWait = prevSlotCMs.map(cm => cm.deployedWait.getOrElse(cm.waitTime)).max

          val waitIcon = (qWait, prevSlotQWait) match {
            case (p, c) if p > c => Icon.arrowDown
            case (p, c) if p < c => Icon.arrowUp
            case _ => Icon.arrowRight
          }

          <.div(^.className := s"queue-box ${TerminalDesksAndQueuesRow.slaRagStatus(qWait, p.airportConfig.slaByQueue(q))}",
            <.div(^.className := "queue-name", s"${Queues.queueDisplayNames.getOrElse(q, q)}"),
            <.div(^.className := "queue-box-text", Icon.users, s"$qPax pax joining"),
            <.div(^.className := "queue-box-text", Icon.clockO, s"$qWait min wait time"),
            <.div(^.className := "queue-box-text", waitIcon, s"queue time"),
          )
        }).toTagMod
      )
    })
    .componentDidMount(p => Callback {
      GoogleEventTracker.sendPageView(s"terminal-dashboard-${p.props.terminal}")
    })
    .build

  def apply(terminal: TerminalName, airportConfig: AirportConfig, portState: PortState, minuteTicker: Int): VdomElement = component(Props(terminal, airportConfig, portState))

  def timeSlotForTime(slotSize: Int)(sd: SDateLike): SDateLike = {
    val offset: Int = sd.getMinutes() % slotSize

    sd.addMinutes(offset * -1)
  }
}
