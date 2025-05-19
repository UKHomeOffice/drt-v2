package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.SPAMain.{Loc, PortDashboardLoc, TerminalPageTabLoc}
import drt.client.components.TerminalDashboardComponent.{defaultSlotSize, timeSlotForTime}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.ViewLive
import drt.shared.CrunchApi._
import drt.shared._
import drt.shared.api.{FlightManifestSummary, WalkTimes}
import drt.shared.redlist.RedList
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^.{<, _}
import japgolly.scalajs.react.{CtorType, ReactEventFromInput, ScalaComponent}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.model.CrunchMinute
import uk.gov.homeoffice.drt.ports.Queues.{InvalidQueue, Queue}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, PaxTypeAndQueue, PortCode, Queues}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{MilliDate, SDateLike}
import drt.client.components.TerminalContentComponent.originMapper

import scala.collection.immutable.HashSet
import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.URIUtils
import scala.util.Try

object DashboardTerminalSummary {

  case class DashboardSummary(
                               startTime: MillisSinceEpoch,
                               numFlights: Int,
                               paxPerQueue: Map[Queue, Double]
                             )

  def pcpHighest(cms: Seq[CrunchMinute]): CrunchMinute = cms.reduceLeft((cm1, cm2) => if (cm1.paxLoad > cm2.paxLoad) cm1 else cm2)

  def pcpLowest(cms: Seq[CrunchMinute]): CrunchMinute = cms.reduceLeft((cm1, cm2) => if (cm1.paxLoad < cm2.paxLoad) cm1 else cm2)

  private def hourRange(start: SDateLike, numHours: Int): IndexedSeq[SDateLike] = (0 until numHours).map(h => start.addHours(h))

  def aggregateAcrossQueues(startMinutes: List[CrunchMinute], terminal: Terminal): List[CrunchMinute] = {
    val emptyMinute = CrunchMinute(terminal, InvalidQueue, 0L, 0, 0, 0, 0, None, None, None, None, None)

    startMinutes
      .groupBy(_.minute)
      .map {
        case (minute, cms) =>
          cms.foldLeft(emptyMinute) {
            case (minuteSoFar, cm) => CrunchMinute(
              terminal = minuteSoFar.terminal,
              queue = InvalidQueue,
              minute = minute,
              paxLoad = minuteSoFar.paxLoad + cm.paxLoad,
              workLoad = minuteSoFar.workLoad + cm.workLoad,
              deskRec = List(minuteSoFar.deskRec, cm.deskRec).sum,
              waitTime = List(minuteSoFar.waitTime, cm.waitTime).sum,
              maybePaxInQueue = maxFromOptionals(minuteSoFar.maybePaxInQueue, cm.maybePaxInQueue),
              deployedDesks = maxFromOptionals(minuteSoFar.deployedDesks, cm.deployedDesks),
              deployedWait = maxFromOptionals(minuteSoFar.deployedWait, cm.deployedWait),
              maybeDeployedPaxInQueue = maxFromOptionals(minuteSoFar.maybeDeployedPaxInQueue, cm.maybeDeployedPaxInQueue),
              actDesks = None
            )
          }
      }.toList
  }

  private def maxFromOptionals(maybeInt1: Option[Int], maybeInt2: Option[Int]): Option[Int] =
    maybeInt1.toList ::: maybeInt2.toList match {
      case Nil => None
      case somePaxInQueue => Option(somePaxInQueue.max)
    }

  def hourSummary(flights: List[ApiFlightWithSplits], cms: List[CrunchMinute], start: SDateLike): Seq[DashboardSummary] = {
    val groupedFlights: Map[MillisSinceEpoch, Set[ApiFlightWithSplits]] = groupFlightsByHour(flights, start).toMap
    val groupedCrunchMinutes = groupCrunchMinutesByHour(cms, start).toMap

    hourRange(start, 3).map(h => DashboardSummary(
      h.millisSinceEpoch,
      groupedFlights.getOrElse(h.millisSinceEpoch, Set()).size,
      groupedCrunchMinutes.getOrElse(h.millisSinceEpoch, List())
        .groupBy(_.queue)
        .view.mapValues(q => q.map(cm => cm.paxLoad).sum).toMap
    ))
  }

  def groupFlightsByHour(flights: List[ApiFlightWithSplits], startMin: SDateLike): Seq[(MillisSinceEpoch, Set[ApiFlightWithSplits])] = {
    val hourInMillis = 3600000
    flights
      .filter { f => f.apiFlight.PcpTime.isDefined }
      .sortBy(_.apiFlight.PcpTime.getOrElse(0L))
      .groupBy(fws => {
        val hoursSinceStart = ((fws.apiFlight.PcpTime.getOrElse(0L) - startMin.millisSinceEpoch) / hourInMillis).toInt
        startMin.addHours(hoursSinceStart).millisSinceEpoch
      })
      .view.mapValues(_.toSet)
      .toList
      .sortBy(_._1)
  }

  private def groupCrunchMinutesByHour(cms: List[CrunchMinute], startMin: SDateLike): Seq[(MillisSinceEpoch, List[CrunchMinute])] = {
    val hourInMillis = 3600000
    cms.sortBy(_.minute).groupBy(cm => {
      val hoursSinceStart = ((cm.minute - startMin.millisSinceEpoch) / hourInMillis).toInt
      startMin.addHours(hoursSinceStart).millisSinceEpoch
    }).toList.sortBy(_._1)
  }

  def flightPcpInPeriod(f: ApiFlightWithSplits, start: SDateLike, end: SDateLike): Boolean =
    f.apiFlight.PcpTime.exists(millis => start.millisSinceEpoch <= millis && millis <= end.millisSinceEpoch)

  def windowStart(time: SDateLike): SDateLike = {

    val minutes = (time.getMinutes / 15) * 15

    SDate(f"${time.getFullYear}-${time.getMonth}%02d-${time.getDate}%02d ${time.getHours}%02d:$minutes%02d")
  }

  def worstTimeslot(crunchMinutes: Seq[CrunchMinute]): CrunchMinute = crunchMinutes.reduceLeft(
    (cm1, cm2) => if (deployedRatio(cm1) > deployedRatio(cm2)) cm1 else cm2
  )

  private def deployedRatio(cm1: CrunchMinute): Double = {
    cm1.deployedDesks match {
      case Some(deployed) =>
        cm1.deskRec.toDouble / deployed
      case None =>
        cm1.deskRec
    }
  }

  def aggSplits(paxFeedSourceOrder: List[FeedSource], flights: Seq[ApiFlightWithSplits]): Map[PaxTypeAndQueue, Int] =
    BigSummaryBoxes.aggregateSplits(flights, paxFeedSourceOrder)

  case class Props(portDashboardLoc: PortDashboardLoc,
                   airportConfig: AirportConfig,
                   //                   slaConfigs: Pot[SlaConfigs],
                   router: RouterCtl[Loc],
                   featureFlags: Pot[FeatureFlags],
                   loggedInUser: LoggedInUser,
                   redListPorts: Pot[HashSet[PortCode]],
                   redListUpdates: RedListUpdates,
                   walkTimes: Pot[WalkTimes],
                   portState: Pot[PortState],
                   flightManifestSummaries: Map[ManifestKey, FlightManifestSummary],
                   arrivalSources: Option[(UniqueArrival, Pot[List[Option[FeedSourceArrival]]])],
                   flightHighlight: FlightHighlight,
                   userPreferences: UserPreferences,
                   flights: List[ApiFlightWithSplits],
                   crunchMinutes: List[CrunchMinute],
                   staffMinutes: List[StaffMinute],
                   terminal: Terminal,
                   paxTypeAndQueues: Iterable[PaxTypeAndQueue],
                   queues: Seq[Queue],
                   timeWindowStart: SDateLike,
                   timeWindowEnd: SDateLike,
                   paxFeedSourceOrder: List[FeedSource],
                  ) extends UseValueEq

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("SummaryBox")
    .render_P { props =>
      val crunchMinuteTimeSlots = groupCrunchMinutesBy(groupSize = 15)(
        CrunchApi.terminalMinutesByMinute(props.crunchMinutes, props.terminal),
        props.terminal,
        Queues.queueOrder).flatMap(_._2)

      if (crunchMinuteTimeSlots.isEmpty) {
        <.div(^.className := "dashboard-summary container-fluid", "No data available to display")
      } else {

        val pressurePoint = worstTimeslot(aggregateAcrossQueues(crunchMinuteTimeSlots.toList, props.terminal))

        def pressureStaffMinute: Option[StaffMinute] = props.staffMinutes.find(_.minute == pressurePoint.minute)

        val pressurePointAvailableStaff = pressureStaffMinute.map(sm => sm.availableAtPcp).getOrElse(0)
        //        val ragClass = TerminalDesksAndQueuesRow.ragStatus(pressurePoint.deskRec, pressurePointAvailableStaff)

        val splitsForPeriod: Map[PaxTypeAndQueue, Int] = aggSplits(props.paxFeedSourceOrder, props.flights)
        val summary: Seq[DashboardSummary] = hourSummary(props.flights, props.crunchMinutes, props.timeWindowStart)
        val queueTotals = totalsByQueue(summary)

        val totalPaxAcrossQueues: Int = queueTotals.values.sum.toInt
        val pcpLowestTimeSlot = pcpLowest(aggregateAcrossQueues(crunchMinuteTimeSlots.toList, props.terminal)).minute

        val pcpHighestTimeSlot = pcpHighest(aggregateAcrossQueues(crunchMinuteTimeSlots.toList, props.terminal)).minute

        def createChartData(splitsForPeriod: Map[PaxTypeAndQueue, Int]): ChartData = {
          val labels = splitsForPeriod.keys.map(_.displayName).toJSArray
          val data = splitsForPeriod.values.toJSArray

          ChartData(
            labels = labels,
            datasets = js.Array(
              Dataset(
                data = data,
                backgroundColor = js.Array("#0E2560", "#334F96", "#547A00", "#CD5B82")
              )
            )
          )
        }

        // Define a function to create the PaxTerminalOverviewComponent
        def renderPaxTerminalOverview(summary: Seq[DashboardSummary], splitsForPeriod: Map[PaxTypeAndQueue, Int]) = {
          PaxTerminalOverviewComponent(IPaxTerminalOverview(
            currentTime = SDate.now().prettyTime,
            desks = pressurePoint.deskRec + pressureStaffMinute.map(_.fixedPoints).getOrElse(0),
            staff = pressurePointAvailableStaff,
            flights = new js.Array(props.flights.size),
            chartData = createChartData(splitsForPeriod),
            pressure = js.Array(
              Pressure(
                pressure = s"+",
                from = SDate(MilliDate(pcpHighestTimeSlot)).prettyTime,
                to = SDate(MilliDate(pcpHighestTimeSlot)).addMinutes(15).prettyTime
              ),
              Pressure(
                pressure = s"-",
                from = SDate(MilliDate(pcpLowestTimeSlot)).prettyTime,
                to = SDate(MilliDate(pcpLowestTimeSlot)).addMinutes(15).prettyTime
              )
            ),
            estimates =
              summary.map { s =>
                Estimate(
                  from = SDate(s.startTime).prettyTime,
                  to = SDate(s.startTime).addMinutes(15).prettyTime,
                  egate = s.paxPerQueue.getOrElse(Queues.EGate, 0).asInstanceOf[Int],
                  eea = s.paxPerQueue.getOrElse(Queues.EeaDesk, 0).asInstanceOf[Int],
                  noneea = s.paxPerQueue.getOrElse(Queues.NonEeaDesk, 0).asInstanceOf[Int]
                )
              }.toJSArray

          ))
        }

        val paxTerminalOverviewComponents = renderPaxTerminalOverview(summary, splitsForPeriod)
        val defaultSlotSize = 120

        val slotSize = Try {
          props.portDashboardLoc.subMode.toInt
        }.getOrElse(defaultSlotSize)

        <.div(^.className := "terminal-dashboard-side",
          props.router
            .link(props.portDashboardLoc.copy(
              queryParams = props.portDashboardLoc.queryParams + ("showArrivals" -> "true")
            ))(^.className := "terminal-dashboard-side__sidebar_widget", "View Arrivals"),
          <.div(
            ^.className := "terminal-dashboard-side__sidebar_widget time-slot-changer",
            <.label(^.className := "terminal-dashboard-side__sidebar_widget__label",
              ^.aria.label := "Select timeslot size for PCP passengers display", "Time slot duration"),
            <.select(
              ^.onChange ==> ((e: ReactEventFromInput) =>
                props.router.set(props.portDashboardLoc.copy(subMode = e.target.value))),
              ^.value := slotSize,
              <.option("15 minutes", ^.value := "15"),
              <.option("30 minutes", ^.value := "30"),
              <.option("1 hour", ^.value := "60"),
              <.option("2 hours", ^.value := "120"),
              <.option("3 hours", ^.value := "180")))
        )

        <.div(paxTerminalOverviewComponents)

        def timeSlotStart: SDateLike => SDateLike = timeSlotForTime(slotSize)

        val startPoint = props.portDashboardLoc.queryParams.get("start")
          .flatMap(s => SDate.parse(s))
          .getOrElse(SDate.now())
        val start = timeSlotStart(startPoint)
        val end = start.addMinutes(slotSize)
        val prevSlotStart = start.addMinutes(-slotSize)

        val urlPrevTime = URIUtils.encodeURI(prevSlotStart.toISOString)
        val urlNextTime = URIUtils.encodeURI(end.toISOString)

//        val terminal = props.portDashboardLoc.terminal

        val portStateForWindow = props.portState.map(_.window(start, end, props.paxFeedSourceOrder))

        val pot = for {
          featureFlags <- props.featureFlags
          redListPorts <- props.redListPorts
          walkTimes <- props.walkTimes
          portState <- portStateForWindow
        } yield {
          if (props.portDashboardLoc.queryParams.contains("showArrivals")) {
            val closeArrivalsPopupLink = props.portDashboardLoc.copy(
              queryParams = props.portDashboardLoc.queryParams - "showArrivals"
            )
            <.div(<.div(^.className := "popover-overlay",
              ^.onClick --> props.router.set(closeArrivalsPopupLink)),
              <.div(^.className := "dashboard-arrivals-popup",
                <.div(^.className := "terminal-dashboard__arrivals_popup_table",
                  FlightTable(
                    FlightTable.Props(
                      queueOrder = props.airportConfig.queueTypeSplitOrder(props.terminal),
                      hasEstChox = props.airportConfig.hasEstChox,
                      loggedInUser = props.loggedInUser,
                      viewMode = ViewLive,
                      hasTransfer = props.airportConfig.hasTransfer,
                      displayRedListInfo = featureFlags.displayRedListInfo,
                      redListOriginWorkloadExcluded = RedList.redListOriginWorkloadExcluded(props.airportConfig.portCode, props.terminal),
                      terminal = props.terminal,
                      portCode = props.airportConfig.portCode,
                      redListPorts = redListPorts,
                      airportConfig = props.airportConfig,
                      redListUpdates = props.redListUpdates,
                      walkTimes = walkTimes,
                      showFlagger = false,
                      paxFeedSourceOrder = props.paxFeedSourceOrder,
                      flightHighlight = props.flightHighlight,
                      flights = portStateForWindow.map(_.flights.values.toSeq),
                      flightManifestSummaries = props.flightManifestSummaries,
                      arrivalSources = props.arrivalSources,
                      originMapper = originMapper,
                      userPreferences = props.userPreferences,
                      terminalPageTab = props.portDashboardLoc
                    )
                  )
                ),
                props.router.link(closeArrivalsPopupLink)(^.className := "close-arrivals-popup btn btn-default", "close")
              ))
          } else <.div()
        }
        <.div(pot.render(identity))
      }
    }.build

  def totalsByQueue(summary: Seq[DashboardSummary]): Map[Queue, MillisSinceEpoch] = summary
    .map {
      case DashboardSummary(_, _, byQ) => byQ
    }
    .flatMap(h => h.toList)
    .groupBy { case (queue, _) => queue }
    .view.mapValues(_.map { case (_, queuePax) => Math.round(queuePax) }.sum).toMap

  def apply(props: Props): Unmounted[Props, Unit, Unit] = component(props)
}
