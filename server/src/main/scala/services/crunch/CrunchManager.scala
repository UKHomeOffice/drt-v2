package services.crunch

import actors.persistent.QueueLikeActor.UpdatedMillis
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.FlightsApi.RemoveSplitsForDateRange
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.ExecutionContext

object CrunchManager {
  def queueDaysToReCrunch(crunchManager: ActorRef, offsetMinutes: Int, forecastMaxDays: Int, now: () => SDateLike): Unit = {
    val today = now()
    val millisToCrunchStart = Crunch.crunchStartWithOffset(offsetMinutes) _
    val daysToReCrunch = (0 until forecastMaxDays).map(d => {
      millisToCrunchStart(today.addDays(d)).millisSinceEpoch
    })
    crunchManager ! UpdatedMillis(daysToReCrunch)
  }

  def queueDaysToReCrunchWithUpdatedSplits(flightsActor: ActorRef, crunchManager: ActorRef, offsetMinutes: Int, forecastMaxDays: Int, now: () => SDateLike)
                                          (implicit timeout: Timeout, ec: ExecutionContext): Unit = {
    val start = now().getLocalLastMidnight.addMinutes(offsetMinutes)
    val endMillis = start.addDays(forecastMaxDays).millisSinceEpoch

    flightsActor
      .ask(RemoveSplitsForDateRange(start.millisSinceEpoch, endMillis))
      .map(_ => queueDaysToReCrunch(crunchManager, offsetMinutes, forecastMaxDays, now))
  }
}
