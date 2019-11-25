package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import drt.shared.splits.ApiSplitsToSplitRatio
import japgolly.scalajs.react.{CtorType, ScalaComponent}
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.vdom.html_<^.{<, _}


object DashboardTerminalSummary {

  case class DashboardSummary(
                               startTime: MillisSinceEpoch,
                               numFlights: Int,
                               paxPerQueue: Map[QueueName, Double]
                             )

  def pcpHighest(cms: Seq[CrunchMinute]): CrunchMinute = cms.reduceLeft((cm1, cm2) => if (cm1.paxLoad > cm2.paxLoad) cm1 else cm2)

  def pcpLowest(cms: Seq[CrunchMinute]): CrunchMinute = cms.reduceLeft((cm1, cm2) => if (cm1.paxLoad < cm2.paxLoad) cm1 else cm2)

  def hourRange(start: SDateLike, numHours: Int): IndexedSeq[SDateLike] = (0 until numHours).map(h => start.addHours(h))

  def aggregateAcrossQueues(startMinutes: List[CrunchMinute], terminalName: TerminalName): List[CrunchMinute] = {
    val emptyMinute = CrunchMinute(terminalName, "", 0L, 0, 0, 0, 0, None, None, None, None, None)

    startMinutes
      .groupBy(_.minute)
      .map {
        case (minute, cms) =>
          cms.foldLeft(emptyMinute) {
            case (minuteSoFar, cm) => CrunchMinute(
              minuteSoFar.terminalName,
              "All",
              minute,
              minuteSoFar.paxLoad + cm.paxLoad,
              minuteSoFar.workLoad + cm.workLoad,
              minuteSoFar.deskRec + cm.deskRec,
              minuteSoFar.waitTime + cm.waitTime,
              Option(minuteSoFar.deployedDesks.getOrElse(0) + cm.deployedDesks.getOrElse(0)),
              Option(minuteSoFar.deployedWait.getOrElse(0) + cm.deployedWait.getOrElse(0)),
              Option(minuteSoFar.deployedWait.getOrElse(0) + cm.deployedWait.getOrElse(0)),
              Option(minuteSoFar.actDesks.getOrElse(0) + cm.actDesks.getOrElse(0)),
              None
            )
          }
      }.toList
  }

  def hourSummary(flights: List[ApiFlightWithSplits], cms: List[CrunchMinute], start: SDateLike): Seq[DashboardSummary] = {
    val groupedFlights: Map[MillisSinceEpoch, Set[ApiFlightWithSplits]] = groupFlightsByHour(flights, start).toMap
    val groupedCrunchMinutes = groupCrunchMinutesByHour(cms, start).toMap

    hourRange(start, 3).map(h => DashboardSummary(
      h.millisSinceEpoch,
      groupedFlights.getOrElse(h.millisSinceEpoch, Set()).size,
      groupedCrunchMinutes.getOrElse(h.millisSinceEpoch, List())
        .groupBy(_.queueName)
        .mapValues(q => q.map(cm => cm.paxLoad).sum))
    )
  }

  def groupFlightsByHour(flights: List[ApiFlightWithSplits], startMin: SDateLike): Seq[(MillisSinceEpoch, Set[ApiFlightWithSplits])] = {
    val hourInMillis = 3600000
    flights.filter { f => f.apiFlight.PcpTime.isDefined }.sortBy(_.apiFlight.PcpTime.getOrElse(0L)).groupBy(fws => {
      val hoursSinceStart = ((fws.apiFlight.PcpTime.getOrElse(0L) - startMin.millisSinceEpoch) / hourInMillis).toInt
      startMin.addHours(hoursSinceStart).millisSinceEpoch
    }).mapValues(_.toSet).toList.sortBy(_._1)
  }

  def groupCrunchMinutesByHour(cms: List[CrunchMinute], startMin: SDateLike): Seq[(MillisSinceEpoch, List[CrunchMinute])] = {
    val hourInMillis = 3600000
    cms.sortBy(_.minute).groupBy(cm => {
      val hoursSinceStart = ((cm.minute - startMin.millisSinceEpoch) / hourInMillis).toInt
      startMin.addHours(hoursSinceStart).millisSinceEpoch
    }).toList.sortBy(_._1)
  }

  def flightPcpInPeriod(f: ApiFlightWithSplits, start: SDateLike, end: SDateLike): Boolean =
    f.apiFlight.PcpTime.exists(millis => start.millisSinceEpoch <= millis && millis <= end.millisSinceEpoch)

  def windowStart(time: SDateLike): SDateLike = {

    val minutes = (time.getMinutes() / 15) * 15

    SDate(f"${time.getFullYear()}-${time.getMonth()}%02d-${time.getDate()}%02d ${time.getHours()}%02d:$minutes%02d")
  }

  def worstTimeslot(crunchMinutes: Seq[CrunchMinute]): CrunchMinute = crunchMinutes.reduceLeft(
    (cm1, cm2) => if (deployedRatio(cm1) > deployedRatio(cm2)) cm1 else cm2
  )

  def deployedRatio(cm1: CrunchMinute): Double = {
    cm1.deployedDesks match {
      case Some(deployed) =>
        cm1.deskRec.toDouble / deployed
      case None =>
        cm1.deskRec
    }
  }

  def aggSplits: Seq[ApiFlightWithSplits] => Map[PaxTypeAndQueue, Int] = BigSummaryBoxes.aggregateSplits(ArrivalHelper.bestPax)

  def paxInPeriod(cms: Seq[CrunchMinute]): Double = cms.map(_.paxLoad).sum

  case class Props(flights: List[ApiFlightWithSplits], crunchMinutes: List[CrunchMinute], staffMinutes: List[StaffMinute], terminal: TerminalName, paxTypeAndQueues: Seq[PaxTypeAndQueue], queues: Seq[QueueName], timeWindowStart: SDateLike, timeWindowEnd: SDateLike)

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("SummaryBox")
    .render_P { props =>
      val crunchMinuteTimeSlots = groupCrunchMinutesByX(groupSize = 15)(CrunchApi.terminalMinutesByMinute(props.crunchMinutes, props.terminal), props.terminal, Queues.queueOrder).flatMap(_._2)

      if (crunchMinuteTimeSlots.isEmpty) {
        <.div(^.className := "dashboard-summary container-fluid", "No data available to display")
      } else {

        val pressurePoint = worstTimeslot(aggregateAcrossQueues(crunchMinuteTimeSlots.toList, props.terminal))
        val ragClass = TerminalDesksAndQueuesRow.ragStatus(pressurePoint.deskRec, pressurePoint.deployedDesks.getOrElse(0))

        val splitsForPeriod: Map[PaxTypeAndQueue, Int] = aggSplits(props.flights)

        val summary: Seq[DashboardSummary] = hourSummary(props.flights, props.crunchMinutes, props.timeWindowStart)
        val queueTotals = totalsByQueue(summary)
        val totalPaxAcrossQueues = queueTotals.values.sum

        val pcpLowestTimeSlot = pcpLowest(aggregateAcrossQueues(crunchMinuteTimeSlots.toList, props.terminal)).minute
        val pcpHighestTimeSlot = pcpHighest(aggregateAcrossQueues(crunchMinuteTimeSlots.toList, props.terminal)).minute

        def pressureStaffMinute = props.staffMinutes.find(_.minute == pressurePoint.minute)

        <.div(^.className := "dashboard-summary container-fluid",
          <.div(^.className := s"$ragClass summary-box-container rag-summary col-sm-1",
            <.span(^.className := "flights-total", f"${props.flights.size}%,d Flights"),
            <.table(^.className := s"summary-box-count rag-desks",
              <.tbody(
                <.tr(
                  <.th(^.colSpan := 2, s"${SDate(MilliDate(pressurePoint.minute)).prettyTime()}")
                ),
                <.tr(
                  <.td("Staff"), <.td("Desks")
                ),
                <.tr(
                  <.td(s"${pressureStaffMinute.map(sm => sm.available).getOrElse(0)}"),
                  <.td(s"${pressurePoint.deskRec + pressureStaffMinute.map(_.fixedPoints).getOrElse(0)}")
                )
              )
            )),
          <.div(^.className := "summary-box-container pax-count col-sm-1", <.div(s"$totalPaxAcrossQueues Pax")),
          <.div(^.className := "summary-box-container col-sm-1", BigSummaryBoxes.GraphComponent("aggregated", "", splitsForPeriod.values.sum, splitsForPeriod, props.paxTypeAndQueues)),
          <.div(^.className := "summary-box-container col-sm-4 dashboard-summary__pax-summary",
            <.table(^.className := "dashboard-summary__pax-summary-table",
              <.tbody(
                <.tr(^.className := "dashboard-summary__pax-summary-row",
                  <.th(^.colSpan := 2, ^.className := "dashboard-summary__pax-summary-cell pax-summary-cell--left", "Time Range"),
                  <.th("Flights", ^.className := "dashboard-summary__pax-summary-cell pax-summary-cell--right"),
                  <.th("Total Pax", ^.className := "dashboard-summary__pax-summary-cell pax-summary-cell--right"), props.queues.map(q =>
                    <.th(Queues.queueDisplayNames(q), ^.className := "dashboard-summary__pax-summary-cell pax-summary-cell--right")).toTagMod),
                summary.map {

                  case DashboardSummary(start, numFlights, paxPerQueue) =>

                    val totalPax = paxPerQueue.values.map(Math.round).sum
                    <.tr(^.className := "dashboard-summary__pax-summary-row",
                      <.td(^.colSpan := 2, ^.className := "dashboard-summary__pax-summary-cell pax-summary-cell--left", s"${SDate(MilliDate(start)).prettyTime()} - ${SDate(MilliDate(start)).addHours(1).prettyTime()}"),
                      <.td(s"$numFlights", ^.className := "dashboard-summary__pax-summary-cell pax-summary-cell--right"),
                      <.td(s"$totalPax", ^.className := "dashboard-summary__pax-summary-cell pax-summary-cell--right"),
                      props.queues.map(q => <.td(s"${Math.round(paxPerQueue.getOrElse(q, 0.0))}", ^.className := "dashboard-summary__pax-summary-cell pax-summary-cell--right")).toTagMod
                    )
                }.toTagMod,
                <.tr(^.className := "dashboard-summary__pax-summary-row",
                  <.th(^.colSpan := 2, ^.className := "dashboard-summary__pax-summary-cell heading pax-summary-cell--left", "3 Hour Total"),
                  <.th(props.flights.size, ^.className := "dashboard-summary__pax-summary-cell pax-summary-cell--right"),
                  <.th(totalPaxAcrossQueues, ^.className := "dashboard-summary__pax-summary-cell pax-summary-cell--right"),
                  props.queues.map(q => <.th(s"${queueTotals.getOrElse(q, 0.0)}", ^.className := "dashboard-summary__pax-summary-cell pax-summary-cell--right")).toTagMod
                )
              )
            )
          ),
          <.div(^.className := "summary-box-container col-sm-1 pcp-summary",
            <.div(^.className := "pcp-pressure",
              <.div(^.className := "title", "PCP Pressure"),
              <.div(^.className := "highest",
                Icon.chevronUp, s"${SDate(MilliDate(pcpHighestTimeSlot)).prettyTime()}-${SDate(MilliDate(pcpHighestTimeSlot)).addMinutes(15).prettyTime()}"
              ),
              <.div(^.className := "lowest",
                Icon.chevronDown, s"${SDate(MilliDate(pcpLowestTimeSlot)).prettyTime()}-${SDate(MilliDate(pcpLowestTimeSlot)).addMinutes(15).prettyTime()}"
              )
            )
          )
        )
      }
    }.build

  def totalsByQueue(summary: Seq[DashboardSummary]): Map[QueueName, MillisSinceEpoch] = summary
    .map {
      case DashboardSummary(_, _, byQ) => byQ
    }
    .flatMap(h => h.toList)
    .groupBy { case (queueName, _) => queueName }
    .mapValues(queueTotalsByQ => queueTotalsByQ.map { case (_, queuePax) => Math.round(queuePax) }.sum)

  def apply(props: Props): Unmounted[Props, Unit, Unit] = component(props)
}
