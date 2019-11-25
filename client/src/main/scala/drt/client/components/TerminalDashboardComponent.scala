package drt.client.components

import diode.data.Pot
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.components.FlightComponents.SplitsGraph.splitsGraphComponentColoured
import drt.client.components.FlightComponents.paxComp
import drt.client.components.TerminalContentComponent.originMapper
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.CrunchMinute
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ScalaComponent}

import scala.scalajs.js.URIUtils


object TerminalDashboardComponent {

  case class Props(
                    terminalPageTabLoc: TerminalPageTabLoc,
                    airportConfig: AirportConfig,
                    router: RouterCtl[Loc],
                    portState: PortState,
                    featureFlags: Pot[Map[String, Boolean]]
                  )

  val slotSize = 15

  def timeSlotStart: SDateLike => SDateLike = timeSlotForTime(slotSize)

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props](displayName = "TerminalDashboard")
    .render_P(p => {
      val startPoint = p.terminalPageTabLoc.queryParams.get("start")
        .flatMap(s => SDate.stringToSDateLikeOption(s))
        .getOrElse(SDate.now())
      val start = timeSlotStart(startPoint)
      val end = start.addMinutes(slotSize)
      val ps = p.portState.window(start, end, p.airportConfig.queues)
      val prevSlotStart = start.addMinutes(-slotSize)

      val prevSlotPortState = p.portState.window(prevSlotStart, start, p.airportConfig.queues)

      val urlPrevTime = URIUtils.encodeURI(prevSlotStart.toISOString())
      val urlNextTime = URIUtils.encodeURI(end.toISOString())

      val terminalPax = ps.crunchMinutes.collect {
        case (_, cm) if cm.terminalName == p.terminalPageTabLoc.terminal => cm.paxLoad
      }.sum.round

      <.div(^.className := "terminal-dashboard",

        if (p.terminalPageTabLoc.queryParams.get("showArrivals").isDefined) {
          val closeArrivalsPopupLink = p.terminalPageTabLoc.copy(
            queryParams = p.terminalPageTabLoc.queryParams - "showArrivals"
          )
          <.div(<.div(^.className := "popover-overlay",
            ^.onClick --> p.router.set(closeArrivalsPopupLink)),

            <.div(^.className := "dashboard-arrivals-popup",

              FlightsWithSplitsTable.ArrivalsTable(
                None,
                originMapper,
                splitsGraphComponentColoured)(paxComp)(
                FlightsWithSplitsTable.Props(
                  ps.flights.filter { case (ua, _) => ua.terminal == p.terminalPageTabLoc.terminal }.values.toList,
                  p.airportConfig.queueTypeSplitOrder(p.terminalPageTabLoc.terminal),
                  p.airportConfig.hasEstChox
                )
              )
            ),
            p.router.link(closeArrivalsPopupLink)(^.className := "close-arrivals-popup btn btn-default", "close")
          )

        } else <.div()
        ,
        <.div(^.className := "terminal-dashboard-queues",
          <.div(^.className := "pax-bar row", s"$terminalPax passengers presenting at the PCP"),

          <.div(^.className := "row queue-boxes",
            p.airportConfig.nonTransferQueues(p.terminalPageTabLoc.terminal).filterNot(_ == Queues.FastTrack).map((q: String) => {

              val qCMs = cmsForTerminalAndQueue(ps, q, p.terminalPageTabLoc.terminal)
              val prevSlotCMs = cmsForTerminalAndQueue(prevSlotPortState, q, p.terminalPageTabLoc.terminal)
              val qPax = qCMs.map(_.paxLoad).sum.round
              val qWait = maxWaitInPeriod(qCMs)
              val prevSlotQWait = maxWaitInPeriod(prevSlotCMs)

              val waitIcon = (prevSlotQWait, qWait) match {
                case (p, c) if p > c => Icon.arrowDown
                case (p, c) if p < c => Icon.arrowUp
                case _ => Icon.arrowRight
              }

              <.div(^.className := s"queue-box col $q ${TerminalDesksAndQueuesRow.slaRagStatus(qWait, p.airportConfig.slaByQueue(q))}",
                <.div(^.className := "queue-name", s"${Queues.queueDisplayNames.getOrElse(q, q)}"),
                <.div(^.className := "queue-box-text", Icon.users, s"$qPax pax joining"),
                <.div(^.className := "queue-box-text", Icon.clockO, s"$qWait min wait time"),
                <.div(^.className := "queue-box-text", waitIcon, s"queue time"),
              )
            }).toTagMod
          ),

          <.div(^.className := "tb-bar row",
            p.router.link(p.terminalPageTabLoc.copy(queryParams = Map("start" -> s"$urlPrevTime")))(^.className := "dashboard-time-switcher prev-bar col", Icon.angleDoubleLeft),
            <.div(^.className := "time-label col", s"${start.prettyTime()} - ${end.prettyTime()}"),
            p.router.link(p.terminalPageTabLoc.copy(queryParams = Map("start" -> s"$urlNextTime")))(^.className := "dashboard-time-switcher next-bar col", Icon.angleDoubleRight)
          )
        )
        ,
        <.div(^.className := "terminal-dashboard-side",
          p.router
            .link(p.terminalPageTabLoc.copy(
              queryParams = p.terminalPageTabLoc.queryParams + ("showArrivals" -> "true")
            ))(^.className := "show-arrivals-btn", "View Arrivals")
        )
      )
    })
    .componentDidMount(p => Callback {
      GoogleEventTracker.sendPageView(page = s"terminal-dashboard-${p.props.terminalPageTabLoc.terminal}")
    })
    .build


  def cmsForTerminalAndQueue(ps: PortStateLike, queue: QueueName, terminal: TerminalName): Iterable[CrunchMinute] = ps
    .crunchMinutes
    .collect {
      case (tqm, cm) if tqm.queueName == queue && tqm.terminalName == terminal => cm
    }

  def maxWaitInPeriod(cru: Iterable[CrunchApi.CrunchMinute]): Int = if (cru.nonEmpty)
    cru.map(cm => cm.deployedWait.getOrElse(cm.waitTime)).max
  else 0

  def apply(
             terminalPageTabLoc: TerminalPageTabLoc,
             airportConfig: AirportConfig,
             portState: PortState,
             router: RouterCtl[Loc],
             minuteTicker: Int,
             featureFlags: Pot[Map[String, Boolean]]
           ): VdomElement = component(Props(terminalPageTabLoc, airportConfig, router, portState, featureFlags))

  def timeSlotForTime(slotSize: Int)(sd: SDateLike): SDateLike = {
    val offset: Int = sd.getMinutes() % slotSize

    sd.addMinutes(offset * -1)
  }
}
