package services.crunch

import actors.CrunchManagerActor.{ReProcessDates, Recrunch}
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import drt.shared.FlightsApi.RemoveSplitsForDateRange
import org.slf4j.LoggerFactory
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.ExecutionContext

object CrunchManager {
  private val log = LoggerFactory.getLogger(getClass)

  def queueDaysToReProcess(crunchManager: ActorRef, offsetMinutes: Int, forecastMaxDays: Int, now: () => SDateLike, message: Set[Long] => ReProcessDates): Unit = {
    val today = now()
    val millisToCrunchStart = Crunch.crunchStartWithOffset(offsetMinutes) _
    val daysToReCrunch = (0 until forecastMaxDays).map(d => {
      millisToCrunchStart(today.addDays(d)).millisSinceEpoch
    }).toSet
    crunchManager ! message(daysToReCrunch)
  }

  def queueDaysToReCrunchWithUpdatedSplits(flightsActor: ActorRef, crunchManager: ActorRef, offsetMinutes: Int, forecastMaxDays: Int, now: () => SDateLike)
                                          (implicit timeout: Timeout, ec: ExecutionContext): Unit = {
    val start = now().getLocalLastMidnight.addMinutes(offsetMinutes)
    val endMillis = start.addDays(forecastMaxDays).millisSinceEpoch

    flightsActor
      .ask(RemoveSplitsForDateRange(start.millisSinceEpoch, endMillis))
      .map(_ => queueDaysToReProcess(crunchManager, offsetMinutes, forecastMaxDays, now, m => Recrunch(m)))
      .recover {
        case t =>
          log.error("Failed to remove splits for date range", t)
          queueDaysToReProcess(crunchManager, offsetMinutes, forecastMaxDays, now, m => Recrunch(m))
      }
  }
}
