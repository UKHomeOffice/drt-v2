package actors

import actors.PartitionedPortStateActor.GetStateForDateRange
import actors.acking.Acking.AckingAsker
import actors.acking.AckingReceiver.{Ack, StreamFailure}
import actors.routing.FlightsRouterActor
import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.scaladsl.Source
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer, StaffMinute}
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff, SplitsForArrivals}
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._


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

    case ps: PortState =>
      log.info(s"Setting initial port state")
      state = ps
  }

  override val askThenAck: AckingAsker = (actor: ActorRef, message: Any, replyTo: ActorRef) => {
    actor.ask(message).foreach { _ =>
      message match {
        case splits: SplitsForArrivals if splits.updatedMillis.nonEmpty =>
          val query = GetStateForDateRange(splits.updatedMillis.min, splits.updatedMillis.max)
          val eventualSource = actor.ask(query).mapTo[Source[FlightsWithSplits, NotUsed]]
          FlightsRouterActor
            .runAndCombine(eventualSource)
            .foreach {
              case FlightsWithSplits(flights) =>
                val updatedFlights = state.flights ++ flights
                state = state.copy(flights = updatedFlights)
                sendStateToProbe()
            }

        case flightsWithSplitsDiff@ArrivalsDiff(_, _) if flightsWithSplitsDiff.updateMinutes.nonEmpty =>
          val query = GetStateForDateRange(flightsWithSplitsDiff.updateMinutes.min, flightsWithSplitsDiff.updateMinutes.max)
          val eventualSource = actor.ask(query).mapTo[Source[FlightsWithSplits, NotUsed]]
          FlightsRouterActor
            .runAndCombine(eventualSource)
            .foreach {
              case FlightsWithSplits(flights) =>
                val updatedFlights = (state.flights ++ flights) -- flightsWithSplitsDiff.toRemove.map(_.unique)
                state = state.copy(flights = updatedFlights)
                sendStateToProbe()
            }

        case flightsWithSplitsDiff@FlightsWithSplitsDiff(_, _) if flightsWithSplitsDiff.nonEmpty =>
          val minusRemovals = ArrivalsRemoval.removeArrivals(flightsWithSplitsDiff.arrivalsToRemove, state.flights)
          val updatedFlights = minusRemovals ++ flightsWithSplitsDiff.flightsToUpdate.map(fws => (fws.unique, fws))
          state = state.copy(flights = updatedFlights)
          sendStateToProbe()

        case mc: MinutesContainer[_, _] =>
          val minuteMillis = mc.minutes.map(_.minute)
          mc.minutes.headOption match {
            case None => sendStateToProbe()
            case Some(minuteLike) if minuteLike.toMinute.isInstanceOf[CrunchMinute] =>
              actor.ask(GetStateForDateRange(minuteMillis.min, minuteMillis.max)).mapTo[MinutesContainer[CrunchMinute, TQM]]
                .foreach { container =>
                  val updatedMinutes = state.crunchMinutes ++ container.minutes.map(ml => (ml.key, ml.toMinute))
                  state = state.copy(crunchMinutes = updatedMinutes)
                  sendStateToProbe()
                }
            case Some(minuteLike) if minuteLike.toMinute.isInstanceOf[StaffMinute] =>
              actor.ask(GetStateForDateRange(minuteMillis.min, minuteMillis.max)).mapTo[MinutesContainer[StaffMinute, TM]]
                .foreach { container =>
                  val updatedMinutes = state.staffMinutes ++ container.minutes.map(ml => (ml.key, ml.toMinute))
                  state = state.copy(staffMinutes = updatedMinutes)
                  sendStateToProbe()
                }
          }
        case _ => sendStateToProbe()
      }
      replyTo ! Ack
    }
  }

  def sendStateToProbe(): Unit = {
    probe ! state
  }
}
