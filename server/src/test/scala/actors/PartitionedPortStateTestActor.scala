package actors

import actors.PartitionedPortStateActor.GetStateForDateRange
import actors.PartitionedPortStateTestActor.{UpdateStateCrunchMinutes, UpdateStateFlights, UpdateStateStaffMinutes}
import actors.routing.FlightsRouterActor
import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.{StatusReply, ask}
import akka.stream.scaladsl.Source
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer, StaffMinute}
import drt.shared.FlightsApi.PaxForArrivals
import drt.shared._
import uk.gov.homeoffice.drt.actor.acking.Acking.AckingAsker
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.StreamFailure
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.model.{CrunchMinute, TQM}
import uk.gov.homeoffice.drt.ports.FeedSource
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
                                    queues: Map[Terminal, Seq[Queue]],
                                    paxFeedSourceOrder: List[FeedSource],
                                   )
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
      val replyTo = sender()
      if (ps.flights.nonEmpty) {
        flightsActor.ask(ArrivalsDiff(ps.flights.map(_._2.apiFlight), Seq()))
          .flatMap(_ => flightsActor.ask(SplitsForArrivals(ps.flights.map {
            case (ua, fws) => (ua, fws.splits)
          })))
          .map(_ => replyTo ! StatusReply.Ack)
      }
      else replyTo ! StatusReply.Ack
  }

  override val askThenAck: AckingAsker = (actor: ActorRef, message: Any, replyTo: ActorRef) => {
    actor.ask(message).foreach { _ =>
      message match {
        case splits: SplitsForArrivals if splits.splits.keys.nonEmpty =>
          val updatedMillis = splits.splits.keys.map(_.scheduled)
          updateFlights(actor, Seq(), updatedMillis.min, updatedMillis.max)

        case pax: PaxForArrivals if pax.pax.keys.nonEmpty =>
          val updatedMillis = pax.pax.keys.map(_.scheduled)
          updateFlights(actor, Seq(), updatedMillis.min, updatedMillis.max)

        case arrivalsDiff@ArrivalsDiff(_, removals) =>
          val minutesAffected = minutesAffectedByDiff(arrivalsDiff, removals)
          if (minutesAffected.nonEmpty) {
            updateFlights(actor, removals, minutesAffected.min, minutesAffected.max)
          }

        case flightsWithSplitsDiff@FlightsWithSplitsDiff(_, _) if flightsWithSplitsDiff.nonEmpty =>
          updateFlights(
            actor,
            flightsWithSplitsDiff.arrivalsToRemove.collect {
              case ua: UniqueArrival => ua
            },
            flightsWithSplitsDiff.updateMinutes(paxFeedSourceOrder).min,
            flightsWithSplitsDiff.updateMinutes(paxFeedSourceOrder).max
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
      replyTo ! StatusReply.Ack
    }
  }

  private def minutesAffectedByDiff(arrivalsDiff: ArrivalsDiff, removals: Iterable[UniqueArrival]): Iterable[MillisSinceEpoch] = {
    val updateMinutes = arrivalsDiff.toUpdate.values.flatMap(_.pcpRange(paxFeedSourceOrder))
    val flightsToRemove = state.flights.values.filter(fws => removals.toSeq.contains(fws.apiFlight.unique))
    val removalMinutes = flightsToRemove.flatMap(_.apiFlight.pcpRange(paxFeedSourceOrder))
    val minutesAffected = updateMinutes ++ removalMinutes
    minutesAffected
  }

  private def updateFlights(actor: ActorRef, removals: Iterable[UniqueArrival], firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch): Unit = {
    val query = GetStateForDateRange(firstMinute, lastMinute)
    val eventualSource = actor.ask(query).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
    FlightsRouterActor
      .runAndCombine(eventualSource)
      .foreach(flights => self ! UpdateStateFlights(flights, removals))
  }

  def sendStateToProbe(): Unit = {
    probe ! state
  }
}
