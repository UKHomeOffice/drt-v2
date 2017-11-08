package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, groupCrunchMinutesByX, terminalCrunchMinutesByMinute}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.vdom.html_<^.{<, _}

import scala.collection.immutable.Seq

object DashboardComponent {

  case class DashboardSummary(
                               startTime: MillisSinceEpoch,
                               numFlights: Int,
                               paxPerQueue: Map[QueueName, Double]
                             )

  def pcpHighest(cms: Seq[CrunchMinute]) = cms.reduceLeft((cm1, cm2) => if (cm1.paxLoad > cm2.paxLoad) cm1 else cm2)

  def pcpLowest(cms: Seq[CrunchMinute]) = cms.reduceLeft((cm1, cm2) => if (cm1.paxLoad < cm2.paxLoad) cm1 else cm2)

  def hourRange(start: SDateLike, numHours: Int) = (0 until numHours).map(h => start.addHours(h))


  def hourSummary(flights: List[ApiFlightWithSplits], cms: List[CrunchMinute], start: SDateLike): Seq[DashboardSummary] = {
    val groupedFlights = groupFlightsByHour(flights, start).toMap
    val groupedCrunchMinutes = groupCrunchMinutesByHour(cms, start).toMap
    val sum = groupedCrunchMinutes.mapValues(cms => cms.map(_.paxLoad).sum).values.sum

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
    flights.sortBy(_.apiFlight.PcpTime).groupBy(fws => {
      val hoursSinceStart = ((fws.apiFlight.PcpTime - startMin.millisSinceEpoch) / hourInMillis).toInt
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

  def flightPcpInPeriod(f: ApiFlightWithSplits, start: SDateLike, end: SDateLike) =
    start.millisSinceEpoch <= f.apiFlight.PcpTime && f.apiFlight.PcpTime <= end.millisSinceEpoch

  def queueTotals(splits: Map[PaxTypeAndQueue, Int]): Map[QueueName, Int] = {
    splits.foldLeft(Map[QueueName, Int]())((map, ptqc) => {
      ptqc match {
        case (PaxTypeAndQueue(_, q), pax) =>
          map + (q -> (map.getOrElse(q, 0) + pax))
      }
    })
  }

  def queuesFromPaxTypeAndQueue(ptq: List[PaxTypeAndQueue]): Seq[String] = {
    ptq.map {
      case PaxTypeAndQueue(_, q) => q
    }.distinct
  }

  def windowStart(time: SDateLike): SDateLike = {

    val minutes = (time.getMinutes() / 15) * 15

    SDate(f"${time.getFullYear()}-${time.getMonth()}%02d-${time.getDate()}%02d ${time.getHours()}%02d:${minutes}%02d")
  }

  def worstTimeslot(crunchMinutes: Seq[CrunchMinute]) = crunchMinutes.reduceLeft(
    (cm1, cm2) => if (deployedRatio(cm1) > deployedRatio(cm2)) cm1 else cm2
  )

  def deployedRatio(cm1: CrunchMinute): Int = {
    cm1.deployedDesks match {
      case Some(deployed) =>
        cm1.deskRec / deployed
      case None =>
        cm1.deskRec
    }
  }

  val aggSplits = BigSummaryBoxes.aggregateSplits(ArrivalHelper.bestPax) _

  def paxInPeriod(cms: Seq[CrunchMinute]) = cms.map(_.paxLoad).sum

  case class Props(
                    flights: List[ApiFlightWithSplits],
                    crunchMinutes: List[CrunchMinute],
                    terminal: TerminalName,
                    queues: List[PaxTypeAndQueue],
                    timeWindowStart: SDateLike,
                    timeWindowEnd: SDateLike
                  )

  val component = ScalaComponent.builder[Props]("SummaryBox")
    .render_P((p) => {

      val crunchMinuteTimeSlots = groupCrunchMinutesByX(15)(terminalCrunchMinutesByMinute(p.crunchMinutes.toSet, p.terminal), p.terminal, Queues.queueOrder).flatMap(_._2)

      val pressurePoint = worstTimeslot(crunchMinuteTimeSlots)
      val ragClass = TerminalDesksAndQueuesRow.ragStatus(pressurePoint.deskRec, pressurePoint.deployedDesks.getOrElse(0))

      val splitsForPeriod: Map[PaxTypeAndQueue, Int] = aggSplits(p.flights)


      val queueNames = queuesFromPaxTypeAndQueue(p.queues)

      val summary: Seq[DashboardSummary] = hourSummary(p.flights, p.crunchMinutes, p.timeWindowStart)
      val queueTotals = totalsByQueue(summary)
      val totalPaxAcrossQueues = queueTotals.map(_._2).sum
      
      <.div(^.className := "dashboard-summary container-fluid",
        <.div(^.className := s"$ragClass summary-box-container rag-summary col-sm-1",
          <.span(^.className := "flights-total", f"${p.flights.size}%,d Flights"),
          <.table(^.className := s"summary-box-count rag-desks",
            <.tbody(
              <.tr(
                <.th(^.colSpan := 2, s"${SDate(MilliDate(pressurePoint.minute)).prettyTime()}")
              ),
              <.tr(
                <.td("Staff"), <.td("Desks")
              ),
              <.tr(
                <.td(s"${pressurePoint.deployedDesks.getOrElse(0)}"), <.td(s"${pressurePoint.deskRec}")
              )
            )
          )),
        <.div(^.className := "summary-box-container pax-count col-sm-1", <.div(s"${totalPaxAcrossQueues} Pax")),
        <.div(^.className := "summary-box-container col-sm-1", BigSummaryBoxes.GraphComponent("aggregated", "", splitsForPeriod.values.sum, splitsForPeriod, p.queues)),
        <.div(^.className := "summary-box-container col-sm-4 pax-summary",
          <.table(
            <.tbody(
              <.tr(<.th(^.colSpan := 2, ^.className := "heading", "Time Range"), <.th("Flights"), <.th("Total Pax"), queueNames.map(q => <.th(Queues.queueDisplayNames(q))).toTagMod),
              summary.map {

                case DashboardSummary(start, numFlights, paxPerQueue) =>

                  val totalPax = paxPerQueue.values.map(Math.round(_)).sum
                  <.tr(
                    <.td(^.colSpan := 2, ^.className := "heading", s"${SDate(MilliDate(start)).prettyTime()} - ${SDate(MilliDate(start)).addHours(1).prettyTime()}"),
                    <.td(s"${numFlights}"),
                    <.td(s"${totalPax}"),
                    queueNames.map(q => <.td(s"${Math.round(paxPerQueue(q))}")).toTagMod
                  )
              }.toTagMod,
              <.tr(
                <.th(^.colSpan := 2, ^.className := "heading", "3 Hour Total"),
                <.th(p.flights.size),
                <.th(totalPaxAcrossQueues), queueNames.map(q => <.th(s"${queueTotals(q)}")).toTagMod
              )
            )
          )
        ),
        <.div(^.className := "summary-box-container col-sm-1 pcp-summary",
          <.div(^.className := "pcp-pressure",
            <.div(^.className := "title", "PCP Pressure"),
            <.div(^.className := "highest",
              Icon.chevronUp, s"${SDate(MilliDate(pcpHighest(crunchMinuteTimeSlots).minute)).prettyTime()}-${SDate(MilliDate(pcpHighest(crunchMinuteTimeSlots).minute)).addMinutes(15).prettyTime()}"
            ),
            <.div(^.className := "lowest",
              Icon.chevronDown, s"${SDate(MilliDate(pcpLowest(crunchMinuteTimeSlots).minute)).prettyTime()}-${SDate(MilliDate(pcpLowest(crunchMinuteTimeSlots).minute)).addMinutes(15).prettyTime()}"
            )
          )
        )
      )
    }

    )
    .build

  def totalsByQueue(summary: Seq[DashboardSummary]) = {
    summary
      .map {
        case DashboardSummary(_, _, byQ) => byQ
      }
      .flatMap(h => h.toList)
      .groupBy { case (queueName, _) => queueName }
      .mapValues(queuTotalsbyQ => queuTotalsbyQ.map { case (_, queuePax) => Math.round(queuePax) }.sum)
  }

  def apply(props: Props) = component(props)
}
