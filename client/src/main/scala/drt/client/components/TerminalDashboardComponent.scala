package drt.client.components

import drt.client.SPAMain.TerminalPageTabLoc
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.shared._
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ScalaComponent}


object TerminalDashboardComponent {

  case class Props(terminalPageTabLoc: TerminalPageTabLoc, airportConfig: AirportConfig, portState: PortState)

  val slotSize = 15

  def timeSlotStart: SDateLike => SDateLike = timeSlotForTime(slotSize)

  val component = ScalaComponent.builder[Props]("TerminalDashboard")
    .render_P(p => {
      val start = timeSlotStart(SDate.now())
      val end = start.addMinutes(slotSize)
      val ps = p.portState.window(start, end, p.airportConfig.queues)
      val prevSlotPortState: PortStateLike = p.portState.window(start.addMinutes(-slotSize), start, p.airportConfig.queues)

      val terminalPax = ps.crunchMinutes.map {
        _._2.paxLoad
      }.sum.round

      <.div(^.className := "terminal-dashboard",
        <.div(^.className := "pax-bar row", s"$terminalPax passengers presenting at the PCP"),

        <.div(^.className := "row queue-boxes",
          p.airportConfig.nonTransferQueues(p.terminalPageTabLoc.terminal).map((q: String) => {

            val qCMs = ps.crunchMinutes.collect { case (tqm, cm) if tqm.queueName == q => cm }
            val prevSlotCMs = prevSlotPortState.crunchMinutes.collect { case (tqm, cm) if tqm.queueName == q => cm }
            val qPax = qCMs.map(_.paxLoad).sum.round
            val qWait = maxWaitInPeriod(qCMs)
            val prevSlotQWait = maxWaitInPeriod(prevSlotCMs)

            val waitIcon = (qWait, prevSlotQWait) match {
              case (p, c) if p > c => Icon.arrowDown
              case (p, c) if p < c => Icon.arrowUp
              case _ => Icon.arrowRight
            }

            <.div(^.className := s"queue-box col ${TerminalDesksAndQueuesRow.slaRagStatus(qWait, p.airportConfig.slaByQueue(q))}",
              <.div(^.className := "queue-name", s"${Queues.queueDisplayNames.getOrElse(q, q)}"),
              <.div(^.className := "queue-box-text", Icon.users, s"$qPax pax joining"),
              <.div(^.className := "queue-box-text", Icon.clockO, s"$qWait min wait time"),
              <.div(^.className := "queue-box-text", waitIcon, s"queue time"),
            )
          }).toTagMod
        ),
        
        <.div(^.className := "tb-bar row",
          <.div(^.className := "dashboard-time-switcher prev-bar col", "<<"),
          <.div(^.className := "time-label col", s"${start.prettyTime()} - ${end.prettyTime()}"),
          <.div(^.className := "dashboard-time-switcher next-bar col", ">>")
        )
      )
    })
    .componentDidMount(p => Callback {
      GoogleEventTracker.sendPageView(s"terminal-dashboard-${p.props.terminalPageTabLoc.terminal}")
    })
    .build

  def maxWaitInPeriod(cru: Iterable[CrunchApi.CrunchMinute]) = {
    if (cru.nonEmpty)
      cru.map(cm => cm.deployedWait.getOrElse(cm.waitTime)).max
    else 0
  }

  def apply(
             terminalPageTabLoc: TerminalPageTabLoc,
             airportConfig: AirportConfig,
             portState: PortState,
             minuteTicker: Int
           ): VdomElement = component(Props(terminalPageTabLoc, airportConfig, portState))

  def timeSlotForTime(slotSize: Int)(sd: SDateLike): SDateLike = {
    val offset: Int = sd.getMinutes() % slotSize

    sd.addMinutes(offset * -1)
  }
}
