package services

import drt.shared.CrunchApi.{CrunchMinute, ForecastPeriod, ForecastTimeSlot}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._

object CSVData {
  def forecastPeriodToCsv(forecastPeriod: ForecastPeriod) = {
    val sortedDays = forecastPeriod.days.toList.sortBy(_._1)
    val byTimeSlot: Iterable[Iterable[ForecastTimeSlot]] = sortedDays.transpose(_._2.take(96))

    val headings1 = "," + sortedDays.map {
      case (day, _) =>
        s"${SDate(MilliDate(day)).getDate()}/${SDate(MilliDate(day)).getMonth()}"
    }.mkString(",,")
    val headings2 = "Start Time," + sortedDays.map(_ => "Avail,Rec").mkString(",")

    val data = byTimeSlot.map(row => {

      s"${SDate(MilliDate(row.head.startMillis)).toHoursAndMinutes()}" + "," + row.map(
        col => {
          s"${col.available},${col.required}"
        }).mkString(",")
    }).mkString("\n")
    
    List(headings1, headings2, data).mkString("\n")
  }


  def terminalCrunchMinutesToCsvData(cms: Set[CrunchMinute], terminalName: TerminalName, queues: Seq[QueueName]) = {
    val colHeadings = List("Pax", "Wait", "Desks req")
    val headingsLine1 = "," + queues.flatMap(qn => List.fill(colHeadings.length)(qn)).mkString(",")
    val headingsLine2 = "Start," + queues.flatMap(_ => colHeadings).mkString(",")

    val lineEnding = "\n"
    headingsLine1 + lineEnding + headingsLine2 + lineEnding +
      cms
        .filter(_.terminalName == terminalName)
        .filter(cm => SDate(cm.minute).getMinutes() % 15 == 0)
        .groupBy(_.minute)
        .collect {
          case (min, cm) =>
            val queueMinutes = cm.groupBy(_.queueName)
            val terminalData = queues.flatMap(qn => {
              queueMinutes.getOrElse(qn, Nil).toList.flatMap(cm => {
                List(Math.round(cm.paxLoad), Math.round(cm.waitTime), cm.deskRec)
              })
            })
            (min, terminalData)
        }.toList.sortBy(m => m._1).map {
        case (minute, data) =>
          SDate(minute).toHoursAndMinutes() + "," + data.mkString(",")
      }.mkString(lineEnding)
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
    val csvData = flightsWithSplits.map(fws => {

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
