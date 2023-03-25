package actors

import actors.PartitionedPortStateActor.GetStateForDateRange
import actors.PartitionedPortStateTestActor.{UpdateStateCrunchMinutes, UpdateStateFlights, UpdateStateStaffMinutes}
import actors.acking.Acking.AckingAsker
import actors.acking.AckingReceiver.{Ack, StreamFailure}
import actors.routing.FlightsRouterActor
import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.scaladsl.Source
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer, StaffMinute}
import drt.shared.FlightsApi.{PaxForArrivals, SplitsForArrivals}
import drt.shared._
import uk.gov.homeoffice.drt.arrivals.{FlightsWithSplits, FlightsWithSplitsDiff, UniqueArrival}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}

object PartitionedPortStateTestActor {
  case class UpdateStateFlights(fws: FlightsWithSplits, removals: Iterable[UniqueArrival])

  case class UpdateStateCrunchMinutes(container: MinutesContainer[CrunchMinute, TQM])

  case class UpdateStateStaffMinutes(container: MinutesContainer[StaffMinute, TM])
}

class PartitionedPortStateTestActor(probe: ActorRef,
                                    flightsActor: ActorRef,
                                    queuesActor: ActorRef,
                                    staffActor: ActorRef,
                                    queueUpdatesActor: ActorRef,
                                    staffUpdatesActor: ActorRef,
                                    flightUpdatesActor: ActorRef,
                                    now: () => SDateLike,
                                    queues: Map[Terminal, Seq[Queue]])
  extends PartitionedPortStateActor(
    flightsActor,
    queuesActor,
    staffActor,
    queueUpdatesActor,
    staffUpdatesActor,
    flightUpdatesActor,
    now,
    queues,
    InMemoryStreamingJournal) {

  var state: PortState = PortState.empty

  override def receive: Receive = processMessage orElse {
    case StreamFailure(_) =>
      log.info(s"Stream shut down")

    case UpdateStateFlights(fws, removals) =>
      state = state.copy(flights = (state.flights -- removals) ++ fws.flights)
      sendStateToProbe()

    case UpdateStateCrunchMinutes(container) =>
      state = state.copy(crunchMinutes = state.crunchMinutes ++ container.indexed)
      sendStateToProbe()

    case UpdateStateStaffMinutes(container) =>
      state = state.copy(staffMinutes = state.staffMinutes ++ container.indexed)
      sendStateToProbe()

    case ps: PortState =>
      log.info(s"Setting initial port state")
      state = ps
  }

  override val askThenAck: AckingAsker = (actor: ActorRef, message: Any, replyTo: ActorRef) => {
    actor.ask(message).foreach { _ =>
      message match {
        case splits: SplitsForArrivals if splits.splits.keys.nonEmpty =>
          val updatedMillis: Iterable[MillisSinceEpoch] = splits.splits.keys.map(_.scheduled)
          updateFlights(actor, Seq(), updatedMillis.min, updatedMillis.max)

        case pax: PaxForArrivals if pax.pax.keys.nonEmpty =>
          val updatedMillis: Iterable[MillisSinceEpoch] = pax.pax.keys.map(_.scheduled)
          updateFlights(actor, Seq(), updatedMillis.min, updatedMillis.max)

        case arrivalsDiff@ArrivalsDiff(_, removals) if arrivalsDiff.updateMinutes.nonEmpty =>
          updateFlights(actor, removals.map(_.unique), arrivalsDiff.updateMinutes.min, arrivalsDiff.updateMinutes.max)

        case flightsWithSplitsDiff@FlightsWithSplitsDiff(_, _) if flightsWithSplitsDiff.nonEmpty =>
          updateFlights(
            actor,
            flightsWithSplitsDiff.arrivalsToRemove.collect {
              case ua: UniqueArrival => ua
            },
            flightsWithSplitsDiff.updateMinutes.min, flightsWithSplitsDiff.updateMinutes.max
          )

        case mc: MinutesContainer[_, _] =>
          val minuteMillis = mc.minutes.map(_.minute)
          mc.minutes.headOption match {
            case None => sendStateToProbe()
            case Some(minuteLike) if minuteLike.toMinute.isInstanceOf[CrunchMinute] =>
              actor
                .ask(GetStateForDateRange(minuteMillis.min, minuteMillis.max)).mapTo[MinutesContainer[CrunchMinute, TQM]]
                .foreach(container => self ! UpdateStateCrunchMinutes(container))
            case Some(minuteLike) if minuteLike.toMinute.isInstanceOf[StaffMinute] =>
              actor.ask(GetStateForDateRange(minuteMillis.min, minuteMillis.max)).mapTo[MinutesContainer[StaffMinute, TM]]
                .foreach(container => self ! UpdateStateStaffMinutes(container))
          }
        case _ => sendStateToProbe()
      }
      replyTo ! Ack
    }
  }

  private def updateFlights(actor: ActorRef, removals: Iterable[UniqueArrival], firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch): Unit = {
    val query = GetStateForDateRange(firstMinute, lastMinute)
    val eventualSource = actor.ask(query).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
    FlightsRouterActor
      .runAndCombine(eventualSource)
      .foreach(fws => self ! UpdateStateFlights(fws, removals))
  }

  def sendStateToProbe(): Unit = {
    probe ! state
  }
}
