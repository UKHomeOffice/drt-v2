package services

import drt.shared.FlightsApi.{QueueName, TerminalName}
import services.Crunch.CrunchMinute

object DesksAndQueuesCSV {

  def terminalCrunchMinutesToCsvData(cms: Set[CrunchMinute], terminalName: TerminalName, queues: Seq[QueueName]) = {
    val colHeadings = List("Pax", "Wait", "Desks req")
    val headingsLine1 = "," + queues.flatMap(qn => List.fill(colHeadings.length)(qn)).mkString(",")
    val headingsLine2 = "Start," + queues.flatMap(_ => colHeadings).mkString(",")

    val lineEnding = "\n"
    headingsLine1 + lineEnding + headingsLine2 + lineEnding +
      cms
        .filter(_.terminalName == terminalName)
        .filter(_.minute % 15 == 0)
        .groupBy(_.minute)
        .map {
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
}
