package services.crunch.deskrecs

import drt.shared.AirportConfig
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import org.joda.time.DateTime
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch.europeLondonTimeZone

object DeskRecs {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def desksForHourOfDayInUKLocalTime(dateTimeMillis: MillisSinceEpoch, desks: Seq[Int]): Int = {
    val date = new DateTime(dateTimeMillis).withZone(europeLondonTimeZone)
    desks(date.getHourOfDay)
  }

  def minDesksForQueueByMinute(deskRecMinutes: Iterable[MillisSinceEpoch], tn: Terminal, qn: Queue, airportConfig: AirportConfig): List[Int] = deskRecMinutes
    .map(desksForHourOfDayInUKLocalTime(_, airportConfig.minDesksForTerminal(tn).getOrElse(qn, List.fill(24)(0)))).toList

  def maxDesksForQueueByMinute(deskRecMinutes: Iterable[MillisSinceEpoch], tn: Terminal, qn: Queue, airportConfig: AirportConfig): List[Int] = deskRecMinutes
    .map(desksForHourOfDayInUKLocalTime(_, airportConfig.maxDesksForTerminal(tn).getOrElse(qn, List.fill(24)(10)))).toList
}

