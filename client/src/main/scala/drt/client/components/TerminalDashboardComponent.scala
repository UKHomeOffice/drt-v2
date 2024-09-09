package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.components.FlightComponents.SplitsGraph.splitsGraphComponentColoured
import drt.client.components.TerminalContentComponent.originMapper
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.ViewLive
import drt.shared.CrunchApi.CrunchMinute
import drt.shared._
import drt.shared.api.{FlightManifestSummary, WalkTimes}
import drt.shared.redlist.RedList
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, ScalaComponent}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, PortCode, Queues}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.SDateLike

import scala.collection.SortedMap
import scala.collection.immutable.{HashSet, Map}
import scala.scalajs.js.URIUtils
import scala.util.Try


object TerminalDashboardComponent {
  case class Props(terminalPageTabLoc: TerminalPageTabLoc,
                   airportConfig: AirportConfig,
                   slaConfigs: Pot[SlaConfigs],
                   router: RouterCtl[Loc],
                   featureFlags: Pot[FeatureFlags],
                   loggedInUser: LoggedInUser,
                   redListPorts: Pot[HashSet[PortCode]],
                   redListUpdates: RedListUpdates,
                   walkTimes: Pot[WalkTimes],
                   paxFeedSourceOrder: List[FeedSource],
                   crunchMinutes: Pot[SortedMap[TQM, CrunchMinute]],
                   flights: Pot[Seq[ApiFlightWithSplits]],
                   flightManifestSummaries: Map[ArrivalKey, FlightManifestSummary],
                   arrivalSources: Option[(UniqueArrival, Pot[List[Option[FeedSourceArrival]]])],
                   airportInfos: Map[PortCode, Pot[AirportInfo]],
                   flightHighlight: FlightHighlight,
                   viewStart: SDateLike,
                   viewEnd: SDateLike,
                  ) extends UseValueEq

  private val defaultSlotSize = 120

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("TerminalDashboard")
    .render_P { props =>
      val slotSize = Try {
        props.terminalPageTabLoc.subMode.toInt
      }.getOrElse(defaultSlotSize)

      def timeSlotStart: SDateLike => SDateLike = timeSlotForTime(slotSize)

      val startPoint = props.terminalPageTabLoc.queryParams.get("start")
        .flatMap(s => SDate.parse(s))
        .getOrElse(SDate.now())
      val start = timeSlotStart(startPoint)
      val end = start.addMinutes(slotSize)
      val prevSlotStart = start.addMinutes(-slotSize)

      val urlPrevTime = URIUtils.encodeURI(prevSlotStart.toISOString)
      val urlNextTime = URIUtils.encodeURI(end.toISOString)

      val terminal = props.terminalPageTabLoc.terminal

      val pot = for {
        featureFlags <- props.featureFlags
        redListPorts <- props.redListPorts
        walkTimes <- props.walkTimes
        crunchMinutes <- props.crunchMinutes
      } yield {
        val currentSlotPs = crunchMinutes.filter(k => start.millisSinceEpoch <= k._1.minute && k._1.minute < end.millisSinceEpoch)
        val prevSlotPs = crunchMinutes.filter(k => prevSlotStart.millisSinceEpoch <= k._1.minute && k._1.minute < start.millisSinceEpoch)

        val terminalPax = currentSlotPs.collect {
          case (_, cm) if cm.terminal == props.terminalPageTabLoc.terminal => cm.paxLoad
        }.sum.round

        <.div(^.className := "terminal-dashboard",
          if (props.terminalPageTabLoc.queryParams.contains("showArrivals")) {
            val closeArrivalsPopupLink = props.terminalPageTabLoc.copy(
              queryParams = props.terminalPageTabLoc.queryParams - "showArrivals"
            )

            <.div(<.div(^.className := "popover-overlay",
              ^.onClick --> props.router.set(closeArrivalsPopupLink)),
              <.div(^.className := "dashboard-arrivals-popup",
                <.h2("Arrivals"),
                <.div(^.className := "terminal-dashboard__arrivals_popup_table",
                  FlightTable(
                    FlightTable.Props(
                      queueOrder = props.airportConfig.queueTypeSplitOrder(props.terminalPageTabLoc.terminal),
                      hasEstChox = props.airportConfig.hasEstChox,
                      loggedInUser = props.loggedInUser,
                      viewMode = ViewLive,
                      defaultWalkTime = props.airportConfig.defaultWalkTimeMillis(props.terminalPageTabLoc.terminal),
                      hasTransfer = props.airportConfig.hasTransfer,
                      displayRedListInfo = featureFlags.displayRedListInfo,
                      redListOriginWorkloadExcluded = RedList.redListOriginWorkloadExcluded(props.airportConfig.portCode, terminal),
                      terminal = terminal,
                      portCode = props.airportConfig.portCode,
                      redListPorts = redListPorts,
                      airportConfig = props.airportConfig,
                      redListUpdates = props.redListUpdates,
                      walkTimes = walkTimes,
                      viewStart = start,
                      viewEnd = end,
                      showFlagger = false,
                      paxFeedSourceOrder = props.paxFeedSourceOrder,
                      flightHighlight = props.flightHighlight,
                      flights = props.flights,
                      flightManifestSummaries = props.flightManifestSummaries,
                      arrivalSources = props.arrivalSources,
                      airportInfos = props.airportInfos,
                      originMapper = originMapper,
                      splitsGraphComponent = splitsGraphComponentColoured,
                    )
                  )
                ),
                props.router.link(closeArrivalsPopupLink)(^.className := "close-arrivals-popup btn btn-default", "close")
              ))
          } else <.div()
          ,
          <.div(^.className := "terminal-dashboard-queues",
            <.div(^.className := "pax-bar", s"$terminalPax passengers presenting at the PCP"),
            <.div(^.className := "queue-boxes",
              props.airportConfig.nonTransferQueues(terminal).filterNot(_ == Queues.FastTrack).map(q => {
                val qCMs = cmsForTerminalAndQueue(currentSlotPs, q, terminal)
                val prevSlotCMs = cmsForTerminalAndQueue(prevSlotPs, q, terminal)
                val qPax = qCMs.map(_.paxLoad).sum.round
                val qWait = maxWaitInPeriod(qCMs)
                val prevSlotQWait = maxWaitInPeriod(prevSlotCMs)

                val waitIcon = (prevSlotQWait, qWait) match {
                  case (p, c) if p > c => Icon.arrowDown
                  case (p, c) if p < c => Icon.arrowUp
                  case _ => Icon.arrowRight
                }

                props.slaConfigs.render { slaConfigs =>
                  def slas: Map[Queue, Int] = slaConfigs.configForDate(startPoint.millisSinceEpoch).getOrElse(props.airportConfig.slaByQueue)

                  <.dl(^.aria.label := s"Passengers joining queue ${Queues.displayName(q)}",
                    ^.className := s"queue-box col ${q.toString.toLowerCase} ${TerminalDesksAndQueuesRow.slaRagStatus(qWait, slas(q))}",
                    <.dt(^.className := "queue-name", s"${Queues.displayName(q)}"),
                    <.dd(^.className := "queue-box-text", Icon.users, s"$qPax pax joining"),
                    <.dd(^.className := "queue-box-text", Icon.clockO, s"${MinuteAsAdjective(qWait).display} wait"),
                    <.dd(^.className := "queue-box-text", waitIcon, s"queue time")
                  )
                }
              }).toTagMod
            ),
            <.div(^.className := "tb-bar-wrapper",
              props.router.link(props.terminalPageTabLoc.
                copy(queryParams = Map("start" -> s"$urlPrevTime")))
              (^.aria.label := s"View previous $slotSize minutes", ^.className := "dashboard-time-switcher prev-bar col", Icon.angleDoubleLeft),
              <.div(^.className := "tb-bar", ^.aria.label := "current display time range",
                s"${start.prettyTime} - ${end.prettyTime}",
              ),
              props.router.link(props.terminalPageTabLoc.
                copy(queryParams = Map("start" -> s"$urlNextTime")))
              (^.aria.label := s"View next $slotSize minutes", ^.className := "dashboard-time-switcher next-bar col", Icon.angleDoubleRight)
            )
          )
          ,
          <.div(^.className := "terminal-dashboard-side",
            props.router
              .link(props.terminalPageTabLoc.copy(
                queryParams = props.terminalPageTabLoc.queryParams + ("showArrivals" -> "true")
              ))(^.className := "terminal-dashboard-side__sidebar_widget", "View Arrivals"),
            <.div(
              ^.className := "terminal-dashboard-side__sidebar_widget time-slot-changer",
              <.label(^.className := "terminal-dashboard-side__sidebar_widget__label",
                ^.aria.label := "Select timeslot size for PCP passengers display", "Time slot duration"),
              <.select(
                ^.onChange ==> ((e: ReactEventFromInput) =>
                  props.router.set(props.terminalPageTabLoc.copy(subMode = e.target.value))),
                ^.value := slotSize,
                <.option("15 minutes", ^.value := "15"),
                <.option("30 minutes", ^.value := "30"),
                <.option("1 hour", ^.value := "60"),
                <.option("2 hours", ^.value := "120"),
                <.option("3 hours", ^.value := "180")))
          )
        )
      }
      <.div(pot.render(identity))
    }
    .componentDidMount(p => Callback {
      GoogleEventTracker.sendPageView(page = s"terminal-dashboard-${p.props.terminalPageTabLoc.terminal}")
    })
    .build

  private def cmsForTerminalAndQueue(crunchMinutes: SortedMap[TQM, CrunchMinute], queue: Queue, terminal: Terminal): Iterable[CrunchMinute] =
    crunchMinutes
      .collect {
        case (tqm, cm) if tqm.queue == queue && tqm.terminal == terminal => cm
      }

  private def maxWaitInPeriod(cru: Iterable[CrunchApi.CrunchMinute]): Int = if (cru.nonEmpty)
    cru.map(cm => cm.deployedWait.getOrElse(cm.waitTime)).max
  else 0

  def apply(props: Props): VdomElement = component(props)

  def timeSlotForTime(slotSize: Int)(sd: SDateLike): SDateLike = {
    val offset: Int = sd.getMinutes % slotSize

    sd.addMinutes(offset * -1)
  }
}
