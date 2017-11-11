package services

import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import org.slf4j.LoggerFactory

import scala.collection.immutable.Seq

object CSVData {

  val log = LoggerFactory.getLogger(getClass)

  def forecastHeadlineToCSV(headlines: ForecastHeadlineFigures) = {
    val headings = "," + headlines.queueDayHeadlines.map(_.day).toList.sorted.map(
      day => f"${SDate(MilliDate(day)).getDate()}%02d/${SDate(MilliDate(day)).getMonth()}%02d"
    ).mkString(",")
    val queues: String = Queues.exportQueueOrder.flatMap(
      q => {
        headlines.queueDayHeadlines.groupBy(_.queue).get(q).map(
          qhls => (s"${Queues.queueDisplayNames.getOrElse(q, q)}" ::
            qhls
              .toList
              .sortBy(_.day)
              .map(qhl => qhl.paxNos.toString)
            ).mkString(",")
        )
      }
    ).mkString("\n")

    val totalPax = "Total Pax," + headlines
      .queueDayHeadlines
      .groupBy(_.day)
      .toList.sortBy(_._1)
      .map(hl => hl._2.toList.map(_.paxNos).sum)
      .mkString(",")

    val totalWL = "Total Workload," + headlines
      .queueDayHeadlines
      .groupBy(_.day)
      .toList.sortBy(_._1)
      .map(hl => hl._2.toList.map(_.workload).sum)
      .mkString(",")

    List(headings, totalPax, queues, totalWL).mkString("\n")
  }

  def forecastPeriodToCsv(forecastPeriod: ForecastPeriod) = {
    val sortedDays: Seq[(MillisSinceEpoch, Seq[ForecastTimeSlot])] = forecastPeriod.days.toList.sortBy(_._1)
    log.info(s"Forecast CSV Export: Days in period: ${sortedDays.length}")
    val byTimeSlot: Iterable[Iterable[ForecastTimeSlot]] = sortedDays.filter {
      case (millis, forecastTimeSlots) if forecastTimeSlots.length == 96 =>
        log.info(s"Forecast CSV Export: day ${SDate(MilliDate(millis)).toLocalDateTimeString()}" +
          s" first:${SDate(MilliDate(forecastTimeSlots.head.startMillis)).toLocalDateTimeString()}" +
          s" last:${SDate(MilliDate(forecastTimeSlots.last.startMillis)).toLocalDateTimeString()}")
        true
      case (millis, forecastTimeSlots) =>
        log.error(s"Forecast CSV Export: error for ${SDate(MilliDate(millis)).toLocalDateTimeString()} got ${forecastTimeSlots.length} days")
        false
    }.transpose(_._2.take(96))

    val headings = "," + sortedDays.map {
      case (day, _) =>
        f"${SDate(MilliDate(day)).getDate()}%02d/${SDate(MilliDate(day)).getMonth()}%02d - available," +
        f"${SDate(MilliDate(day)).getDate()}%02d/${SDate(MilliDate(day)).getMonth()}%02d - required," +
        f"${SDate(MilliDate(day)).getDate()}%02d/${SDate(MilliDate(day)).getMonth()}%02d - difference"
    }.mkString(",")

    val data = byTimeSlot.map(row => {
      s"${SDate(MilliDate(row.head.startMillis)).toHoursAndMinutes()}" + "," +
        row.map(col => {
          s"${col.available},${col.required},${col.available - col.required}"
        }).mkString(",")
    }).mkString("\n")

    List(headings, data).mkString("\n")
  }


  def terminalCrunchMinutesToCsvData(cms: Set[CrunchMinute], staffMinutes: Set[StaffMinute], terminalName: TerminalName, queues: Seq[QueueName]) = {
    val colHeadings = List("Pax", "Wait", "Desks req", "Act. wait time", "Act. desks")
    val eGatesHeadings = List("Pax", "Wait", "Staff req", "Act. wait time", "Act. desks")
    val headingsLine1 = "," + queues
      .flatMap(qn => List.fill(colHeadings.length)(Queues.exportQueueDisplayNames.getOrElse(qn, qn))).mkString(",") +
      ",Misc,PCP Staff,PCP Staff"
    val headingsLine2 = "Start," + queues.flatMap(q => {
      if (q == Queues.EGate) eGatesHeadings else colHeadings
    }).mkString(",") +
      ",Staff req,Avail,Req"


    val lineEnding = "\n"

    val terminalMinutes = CrunchApi.terminalMinutesByMinute(cms, terminalName)
    val crunchMinutes = CrunchApi.groupCrunchMinutesByX(15)(terminalMinutes, terminalName, queues.toList)
      .collect {
        case (min, cm) =>
          val queueMinutes = cm.groupBy(_.queueName)
          val terminalData = queues.flatMap(qn => {
            queueMinutes.getOrElse(qn, Nil).toList.flatMap(cm => {
              List(
                Math.round(cm.paxLoad).toString,
                Math.round(cm.waitTime).toString,
                cm.deskRec.toString,
                cm.actWait.getOrElse(""),
                cm.actDesks.getOrElse("")
              )
            })
          })

          (min, terminalData)
      }.toList.sortBy(m => m._1).map {
      case (minute, queueData) =>
        val staffData = staffMinutes.find(_.minute == minute).map(sm => List(sm.fixedPoints.toString, sm.available.toString)).getOrElse(List("", ""))
        val reqForMinute = terminalMinutes.toList.find(_._1 == minute).map {
          case (_, cms) => cms.toList.map(_.deskRec).sum
        }.getOrElse(0)

        SDate(minute).toHoursAndMinutes() + "," + queueData.mkString(",") + "," + staffData.mkString(",") + "," + reqForMinute
    }
    headingsLine1 + lineEnding + headingsLine2 + lineEnding +
      crunchMinutes.mkString(lineEnding)
  }

  def flightsWithSplitsToCSV(flightsWithSplits: List[ApiFlightWithSplits]) = {

    def splitFromFlightWithSplits(fws: ApiFlightWithSplits, source: String, paxTypeAndQueue: PaxTypeAndQueue): String = fws.splits
      .find(s => s.source == source)
      .flatMap(as => as.splits.find(s =>
        s.queueType == paxTypeAndQueue.queueType && s.passengerType == paxTypeAndQueue.passengerType
      )).map(ptqc => Math.round(ptqc.paxCount).toString).getOrElse("")

    val headings = "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Arrival,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP," +
      "API EEA Machine Readable to EGate,API EEA Machine Readable to Desk,API EEA Non Machine Readable to Desk," +
      "API Visa National to Desk, API Non-visa National to Desk,API Visa National to Fast-Track,API Non-visa National to Fast Track," +
      "Historic EEA Machine Readable to EGate,Historic EEA Machine Readable to Desk,Historic EEA Non Machine Readable to Desk," +
      "Historic Visa National to Desk, Historic Non-visa National to Desk,Historic Visa National to Fast-Track,Historic Non-visa National to Fast Track"
    val csvData = flightsWithSplits.sortBy(_.apiFlight.PcpTime).map(fws => {

      val flightCsvFields = List(
        fws.apiFlight.IATA,
        fws.apiFlight.ICAO,
        fws.apiFlight.Origin,
        fws.apiFlight.Gate + "/" + fws.apiFlight.Stand,
        fws.apiFlight.Status,
        fws.apiFlight.SchDT,
        fws.apiFlight.EstDT,
        fws.apiFlight.ActDT,
        fws.apiFlight.EstChoxDT,
        fws.apiFlight.ActChoxDT,
        SDate(fws.apiFlight.PcpTime).toISOString(),
        splitFromFlightWithSplits(fws, SplitRatiosNs.SplitSources.ApiSplitsWithCsvPercentage, PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EGate)),
        splitFromFlightWithSplits(fws, SplitRatiosNs.SplitSources.ApiSplitsWithCsvPercentage, PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk)),
        splitFromFlightWithSplits(fws, SplitRatiosNs.SplitSources.ApiSplitsWithCsvPercentage, PaxTypeAndQueue(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk)),
        splitFromFlightWithSplits(fws, SplitRatiosNs.SplitSources.ApiSplitsWithCsvPercentage, PaxTypeAndQueue(PaxTypes.VisaNational, Queues.NonEeaDesk)),
        splitFromFlightWithSplits(fws, SplitRatiosNs.SplitSources.ApiSplitsWithCsvPercentage, PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.NonEeaDesk)),
        splitFromFlightWithSplits(fws, SplitRatiosNs.SplitSources.ApiSplitsWithCsvPercentage, PaxTypeAndQueue(PaxTypes.VisaNational, Queues.FastTrack)),
        splitFromFlightWithSplits(fws, SplitRatiosNs.SplitSources.ApiSplitsWithCsvPercentage, PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.FastTrack)),
        splitFromFlightWithSplits(fws, SplitRatiosNs.SplitSources.Historical, PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EGate)),
        splitFromFlightWithSplits(fws, SplitRatiosNs.SplitSources.Historical, PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk)),
        splitFromFlightWithSplits(fws, SplitRatiosNs.SplitSources.Historical, PaxTypeAndQueue(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk)),
        splitFromFlightWithSplits(fws, SplitRatiosNs.SplitSources.Historical, PaxTypeAndQueue(PaxTypes.VisaNational, Queues.NonEeaDesk)),
        splitFromFlightWithSplits(fws, SplitRatiosNs.SplitSources.Historical, PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.NonEeaDesk)),
        splitFromFlightWithSplits(fws, SplitRatiosNs.SplitSources.Historical, PaxTypeAndQueue(PaxTypes.VisaNational, Queues.FastTrack)),
        splitFromFlightWithSplits(fws, SplitRatiosNs.SplitSources.Historical, PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.FastTrack))
      )

      flightCsvFields
    }).map(_.mkString(",")).mkString("\n")

    headings + "\n" + csvData
  }
}
