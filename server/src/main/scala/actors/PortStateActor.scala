package actors

import akka.actor.{Actor, ActorRef}
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.{AirportConfig, PortStateMutable, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.{DeskRecMinutes, SimulationMinutes}


class PortStateActor(val state: PortStateMutable,
                     liveStateActor: ActorRef,
                     forecastStateActor: ActorRef,
                     airportConfig: AirportConfig,
                     expireAfterMillis: MillisSinceEpoch,
                     now: () => SDateLike,
                     liveDaysAhead: Int) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def receive: Receive = {
    case flights: FlightsWithSplits =>
      val diff = flights.applyTo(state, nowMillis)
    case deskMins: DeskRecMinutes =>
      val diff = deskMins.applyTo(state, nowMillis)
    case deskStats: ActualDeskStats =>
      val diff = deskStats.applyTo(state, nowMillis)
    case staffMins: StaffMinutes =>
      val diff = staffMins.applyTo(state, nowMillis)
    case simMins: SimulationMinutes =>
      val diff = simMins.applyTo(state, nowMillis)
  }

  private def nowMillis = {
    now().millisSinceEpoch
  }
}
