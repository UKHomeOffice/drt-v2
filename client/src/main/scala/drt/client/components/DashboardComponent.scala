package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, groupByX, terminalCrunchMinutesByMinute}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.vdom.html_<^.{<, _}

object DashboardComponent {
  def pcpHighest(cms: Seq[CrunchMinute]) = cms.reduceLeft((cm1, cm2) => if (cm1.paxLoad > cm2.paxLoad) cm1 else cm2)

  def pcpLowest(cms: Seq[CrunchMinute]) = cms.reduceLeft((cm1, cm2) => if (cm1.paxLoad < cm2.paxLoad) cm1 else cm2)

  def groupByHour(flights: List[ApiFlightWithSplits], startMin: SDateLike) = {

    val hourInMillis = 3600000
    flights.sortBy(_.apiFlight.PcpTime).groupBy(fws => {
      startMin.addHours(((fws.apiFlight.PcpTime - startMin.millisSinceEpoch) / hourInMillis).toInt).millisSinceEpoch
    }).mapValues(_.toSet).toList.sortBy(_._1)
  }

  def queueTotals(aggSplits: Map[PaxTypeAndQueue, Int]) = {
    aggSplits.foldLeft(Map[QueueName, Int]())((map, ptqc) => {
      ptqc match {
        case (PaxTypeAndQueue(_, q), pax) =>
          map + (q -> (map.getOrElse(q, 0) + pax))
      }
    })
  }

  def windowStart(time: SDateLike) = {

    val minutes = (time.getMinutes() / 15) * 15

    SDate(f"${time.getFullYear()}-${time.getMonth()}%02d-${time.getDate()}%02d ${time.getHours()}%02d:${minutes}%02d")
  }

  def worstTimeslot(crunchMinutes: Seq[CrunchMinute]) = crunchMinutes.reduceLeft(
    (cm1, cm2) => if (deployedRatio(cm1) > deployedRatio(cm2)) cm1 else cm2
  )

  def deployedRatio(cm1: CrunchMinute) = {
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

      val crunchMinuteTimeSlots = groupByX(15)(terminalCrunchMinutesByMinute(p.crunchMinutes.toSet, p.terminal), p.terminal, Queues.queueOrder).flatMap(_._2)

      val pressurePoint = worstTimeslot(crunchMinuteTimeSlots)
      val ragClass = TerminalDesksAndQueuesRow.ragStatus(pressurePoint.deskRec, pressurePoint.deployedDesks.getOrElse(0))

      val splitsForPeriod = aggSplits(p.flights.toList)
      val totalForQueuesInPeriod = queueTotals(splitsForPeriod)


      val queueNames = p.queues.map {
        case PaxTypeAndQueue(_, q) => q
      }.distinct

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
        <.div(^.className := "summary-box-container pax-count col-sm-1", <.div(s"${paxInPeriod(crunchMinuteTimeSlots).toInt} Pax")),
        <.div(^.className := "summary-box-container col-sm-1", BigSummaryBoxes.GraphComponent("aggregated", "", splitsForPeriod.values.sum, splitsForPeriod, p.queues)),
        <.div(^.className := "summary-box-container col-sm-4 pax-summary",
          <.table(
            <.tbody(
              <.tr(<.th(^.colSpan := 2, ^.className := "heading", "Time Range"), <.th("Flights"), <.th("Total Pax"), queueNames.map(q => <.th(Queues.queueDisplayNames(q))).toTagMod),
              groupByHour(p.flights, p.timeWindowStart).map {

                case (start, flightsWithSplits) =>
                  val numFlights = flightsWithSplits.size
                  val totalPax = flightsWithSplits.map(fws => ArrivalHelper.bestPax(fws.apiFlight)).sum
                  val paxPerQueue = queueTotals(aggSplits(flightsWithSplits.toList))
                  <.tr(
                    <.td(^.colSpan := 2, ^.className := "heading", s"${SDate(MilliDate(start)).prettyTime()} - ${SDate(MilliDate(start)).addHours(1).prettyTime()}"),
                    <.td(s"${numFlights}"),
                    <.td(s"${totalPax}"),
                    queueNames.map(q => <.td(s"${paxPerQueue(q)}")).toTagMod
                  )
              }.toTagMod,
              <.tr(
                <.th(^.colSpan := 2, ^.className := "heading"
                  , "3 Hour Total"),
                <.th(p.flights.size),
                <.th(p.flights.map(fws => ArrivalHelper.bestPax(fws.apiFlight)).sum),
                queueNames.map(q => <.th(totalForQueuesInPeriod(q))).toTagMod
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

    })
    .build

  def apply(props: Props) = component(props)
}

