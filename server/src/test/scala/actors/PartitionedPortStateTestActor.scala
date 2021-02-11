package actors

import actors.PartitionedPortStateActor.{GetStateForDateRange, flightUpdatesProps, queueUpdatesProps, staffUpdatesProps}
import actors.acking.Acking.AckingAsker
import actors.acking.AckingReceiver.{Ack, StreamFailure}
import actors.daily.{FlightUpdatesSupervisor, QueueUpdatesSupervisor, StaffUpdatesSupervisor}
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer, StaffMinute}
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._

import scala.concurrent.ExecutionContext

object PartitionedPortStateTestActor {
  def apply(testProbe: TestProbe, flightsActor: ActorRef, now: () => SDateLike, airportConfig: AirportConfig)
           (implicit system: ActorSystem, ec: ExecutionContext): ActorRef = {
    val lookups = MinuteLookups(system, now, MilliTimes.oneDayMillis, airportConfig.queuesByTerminal)
    val queuesActor = lookups.queueMinutesActor
    val staffActor = lookups.staffMinutesActor
    val queueUpdates = system.actorOf(Props(new QueueUpdatesSupervisor(now, airportConfig.queuesByTerminal.keys.toList, queueUpdatesProps(now, InMemoryStreamingJournal))), "updates-supervisor-queues")
    val staffUpdates = system.actorOf(Props(new StaffUpdatesSupervisor(now, airportConfig.queuesByTerminal.keys.toList, staffUpdatesProps(now, InMemoryStreamingJournal))), "updates-supervisor-staff")
    val flightUpdates = system.actorOf(Props(new FlightUpdatesSupervisor(now, airportConfig.queuesByTerminal.keys.toList, flightUpdatesProps(now, InMemoryStreamingJournal))), "updates-supervisor-flight")
    system.actorOf(Props(new PartitionedPortStateTestActor(testProbe.ref, flightsActor, queuesActor, staffActor, queueUpdates, staffUpdates, flightUpdates, now, airportConfig.queuesByTerminal)))
  }
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

    case ps: PortState =>
      log.info(s"Setting initial port state")
      state = ps
  }

  override val askThenAck: AckingAsker = (actor: ActorRef, message: Any, replyTo: ActorRef) => {
    actor.ask(message).foreach { _ =>
      message match {
        case flightsWithSplitsDiff@FlightsWithSplitsDiff(_, _) if flightsWithSplitsDiff.updateMinutes.nonEmpty =>
          actor.ask(GetStateForDateRange(flightsWithSplitsDiff.updateMinutes.min, flightsWithSplitsDiff.updateMinutes.max)).mapTo[Source[FlightsWithSplits, NotUsed]].foreach {
            _ =>
              val updatedFlights = (state.flights -- flightsWithSplitsDiff.arrivalsToRemove) ++ flightsWithSplitsDiff.flightsToUpdate.map(fws => (fws.unique, fws))
              state = state.copy(flights = updatedFlights)
              sendStateToProbe()
          }

        case flightsWithSplitsDiff@FlightsWithSplitsDiff(_, _) if flightsWithSplitsDiff.nonEmpty =>
          val updatedFlights = (state.flights -- flightsWithSplitsDiff.arrivalsToRemove) ++ flightsWithSplitsDiff.flightsToUpdate.map(fws => (fws.unique, fws))
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
