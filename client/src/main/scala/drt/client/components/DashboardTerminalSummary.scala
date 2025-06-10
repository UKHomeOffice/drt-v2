package drt.client.components

import diode.UseValueEq
import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi._
import drt.shared._
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.vdom.html_<^.{<, _}
import japgolly.scalajs.react.{CtorType, ScalaComponent}
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.models.CrunchMinute
import uk.gov.homeoffice.drt.ports.Queues.{InvalidQueue, Queue}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{FeedSource, PaxTypeAndQueue, Queues}
import uk.gov.homeoffice.drt.time.{MilliDate, SDateLike}
import scala.scalajs.js
import scala.scalajs.js.JSConverters._

object DashboardTerminalSummary {

  case class DashboardSummary(
                               startTime: MillisSinceEpoch,
                               numFlights: Int,
                               paxPerQueue: Map[Queue, Double]
                             )

  def pcpHighest(cms: Seq[CrunchMinute]): CrunchMinute = cms.reduceLeft((cm1, cm2) => if (cm1.paxLoad > cm2.paxLoad) cm1 else cm2)

  def pcpLowest(cms: Seq[CrunchMinute]): CrunchMinute = cms.reduceLeft((cm1, cm2) => if (cm1.paxLoad < cm2.paxLoad) cm1 else cm2)

  private def periodStartTimes(start: SDateLike, numMinutes: Int): IndexedSeq[SDateLike] = (0 until 3).map(m => start.addMinutes(numMinutes * m))

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

  def periodSummaries(flights: List[ApiFlightWithSplits], cms: List[CrunchMinute], start: SDateLike, periodLengthMinutes: Int): Seq[DashboardSummary] = {
    val groupedFlights: Map[MillisSinceEpoch, Set[ApiFlightWithSplits]] = groupFlightsByMinuteRange(flights, start, periodLengthMinutes).toMap
    val groupedCrunchMinutes = groupCrunchByMinutes(cms, start, periodLengthMinutes).toMap
    periodStartTimes(start, periodLengthMinutes).map(h => DashboardSummary(
      h.millisSinceEpoch,
      groupedFlights.getOrElse(h.millisSinceEpoch, Set()).size,
      groupedCrunchMinutes.getOrElse(h.millisSinceEpoch, List())
        .groupBy(_.queue)
        .view.mapValues(q => q.map(cm => cm.paxLoad).sum).toMap
    ))
  }

  def groupFlightsByMinuteRange(
                                 flights: List[ApiFlightWithSplits],
                                 startMin: SDateLike,
                                 periodLengthMinutes: Int
                               ): Seq[(MillisSinceEpoch, Set[ApiFlightWithSplits])] = {
    val periodLengthMillis = periodLengthMinutes * 60 * 1000
    flights
      .filter(_.apiFlight.PcpTime.isDefined)
      .sortBy(_.apiFlight.PcpTime.getOrElse(0L))
      .groupBy { flight =>
        val pcpTime = flight.apiFlight.PcpTime.getOrElse(0L)
        val intervalsSinceStart = ((pcpTime - startMin.millisSinceEpoch) / periodLengthMillis).toInt
        startMin.addMinutes(intervalsSinceStart * periodLengthMinutes).millisSinceEpoch
      }
      .view.mapValues(_.toSet)
      .toList
      .sortBy(_._1)
  }

  private def groupCrunchByMinutes(cms: List[CrunchMinute], startMin: SDateLike, minuteRangeTime: Int): Seq[(MillisSinceEpoch, List[CrunchMinute])] = {
    val periodLengthMillis = minuteRangeTime * 60 * 1000
    cms.sortBy(_.minute).groupBy(cm => {
      val intervalsSinceStart = ((cm.minute - startMin.millisSinceEpoch) / periodLengthMillis).toInt
      startMin.addMinutes((intervalsSinceStart * minuteRangeTime)).millisSinceEpoch
    }).toList.sortBy(_._1)
  }

  def flightPcpInPeriod(f: ApiFlightWithSplits, start: SDateLike, end: SDateLike): Boolean =
    f.apiFlight.PcpTime.exists(millis => start.millisSinceEpoch <= millis && millis <= end.millisSinceEpoch)

  def windowStart(time: SDateLike, periodLengthMinutes: Int): SDateLike = {

    val minutes = (time.getMinutes / periodLengthMinutes) * periodLengthMinutes

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

  case class Props(flights: List[ApiFlightWithSplits],
                   crunchMinutes: List[CrunchMinute],
                   staffMinutes: List[StaffMinute],
                   terminal: Terminal,
                   paxTypeAndQueues: Iterable[PaxTypeAndQueue],
                   queues: Seq[Queue],
                   timeWindowStart: SDateLike,
                   timeWindowEnd: SDateLike,
                   paxFeedSourceOrder: List[FeedSource],
                   periodLengthMinutes: Int,
                   terminalHasBothEeaAndNonEeaQueues: Boolean,
                  ) extends UseValueEq

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("SummaryBox")
    .render_P { props =>
      val crunchMinuteTimeSlots = groupCrunchMinutesBy(groupSize = props.periodLengthMinutes / 3)(
        CrunchApi.terminalMinutesByMinute(props.crunchMinutes, props.terminal),
        props.terminal,
        Queues.queueOrder).flatMap(_._2)

      if (crunchMinuteTimeSlots.isEmpty) {
        <.div(^.className := "dashboard-summary container-fluid", "No data available to display")
      } else {

        val pressurePoint = worstTimeslot(aggregateAcrossQueues(crunchMinuteTimeSlots.toList, props.terminal))

        def pressureStaffMinute: Option[StaffMinute] = props.staffMinutes.find(_.minute == pressurePoint.minute)

        val pressurePointAvailableStaff = pressureStaffMinute.map(sm => sm.availableAtPcp).getOrElse(0)

        val ragClass: String = TerminalDesksAndQueuesRow.ragStatus(pressurePoint.deskRec, pressurePointAvailableStaff)

        val filteredFlights = props.flights.filter(flight =>
          flight.apiFlight.PcpTime.exists(pcpTime =>
            pcpTime >= props.timeWindowStart.millisSinceEpoch && pcpTime <= props.timeWindowStart.addMinutes(props.periodLengthMinutes * 3).millisSinceEpoch
          )
        )
        val splitsForPeriod: Map[PaxTypeAndQueue, Int] = aggSplits(props.paxFeedSourceOrder, filteredFlights)
        val summary: Seq[DashboardSummary] = periodSummaries(filteredFlights, props.crunchMinutes, props.timeWindowStart, props.periodLengthMinutes)

        val pcpLowestTimeSlot = pcpLowest(aggregateAcrossQueues(crunchMinuteTimeSlots.toList, props.terminal)).minute
        val pcpHighestTimeSlot = pcpHighest(aggregateAcrossQueues(crunchMinuteTimeSlots.toList, props.terminal)).minute

        def createChartData(splitsForPeriod: Map[PaxTypeAndQueue, Int]): ChartData = {
          val paxSplitDataset: Seq[(String, Int)] = if (props.terminalHasBothEeaAndNonEeaQueues)
            BigSummaryBoxes.paxSplitPercentagesWithSplitDeskQueues(splitsForPeriod)
          else
            BigSummaryBoxes.paxSplitPercentagesWithSingleDeskQueue(splitsForPeriod)

          val paxSplitDatasetNonZero = paxSplitDataset.filter(_._2 > 0)
          val labels = paxSplitDatasetNonZero.map(_._1).toJSArray
          val data = paxSplitDatasetNonZero.map(_._2).toJSArray.filter(_ > 0)

          ChartData(
            labels = labels,
            datasets = js.Array(
              Dataset(
                data = data,
                backgroundColor = js.Array("#0E2560", "#334F96", "#547A00", "#CD5B82", "#FF6F20", "#00A99D", "#A6A6A6"),
              )
            )
          )
        }

        def renderPaxTerminalOverview(summary: Seq[DashboardSummary], splitsForPeriod: Map[PaxTypeAndQueue, Int]) = {
          PaxTerminalOverviewComponent(IPaxTerminalOverview(
            terminal = props.terminal.toString,
            periodLengthMinutes = props.periodLengthMinutes,
            currentTime = SDate.now().prettyTime,
            desks = pressurePoint.deskRec + pressureStaffMinute.map(_.fixedPoints).getOrElse(0),
            staff = pressurePointAvailableStaff,
            flights = new js.Array(props.flights.size),
            ragStatus = ragClass,
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
              summary.flatMap { s =>
                List(PeriodQueuePaxCounts(
                  from = SDate(s.startTime).prettyTime,
                  to = SDate(s.startTime).addMinutes(props.periodLengthMinutes).prettyTime,
                  egate = s.paxPerQueue.getOrElse(Queues.EGate, 0).asInstanceOf[Int],
                  eea = s.paxPerQueue.getOrElse(Queues.EeaDesk, 0).asInstanceOf[Int],
                  noneea = s.paxPerQueue.getOrElse(Queues.NonEeaDesk, 0).asInstanceOf[Int]
                )).toJSArray
              }.toJSArray

          ))
        }

        val paxTerminalOverviewComponents = renderPaxTerminalOverview(summary, splitsForPeriod)

        <.div(paxTerminalOverviewComponents)

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
