package actors

import actors.acking.AckingReceiver.Ack
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.TestProbe
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer, StaffMinute}
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Terminals.Terminal
import drt.shared._

import scala.collection.immutable.SortedMap
import scala.concurrent.ExecutionContext

object PartitionedPortStateTestActor {
  def apply(testProbe: TestProbe, flightsActor: ActorRef, now: () => SDateLike, airportConfig: AirportConfig)
           (implicit system: ActorSystem, ec: ExecutionContext): ActorRef = {
    val lookups = MinuteLookups(system, now, MilliTimes.oneDayMillis, airportConfig.queuesByTerminal)
    val queuesActor = lookups.queueMinutesActor(classOf[QueueMinutesActor])
    val staffActor = lookups.staffMinutesActor(classOf[StaffMinutesActor])
    system.actorOf(Props(new PartitionedPortStateTestActor(testProbe.ref, flightsActor, queuesActor, staffActor, now, airportConfig.terminals.toList)))
  }
}

class PartitionedPortStateTestActor(probe: ActorRef,
                                    flightsActor: ActorRef,
                                    queuesActor: ActorRef,
                                    staffActor: ActorRef,
                                    now: () => SDateLike,
                                    terminals: List[Terminal]) extends PartitionedPortStateActor(flightsActor, queuesActor, staffActor, now, terminals, TestStreamingJournal) {
  var state: PortState = PortState.empty

  override def receive: Receive = processMessage orElse {
    case ps: PortState =>
      val replyTo = sender()
      log.info(s"Setting initial port state")
      state = ps
      flightsActor.ask(FlightsWithSplitsDiff(ps.flights.values.toList, List())).flatMap { _ =>
        queuesActor.ask(MinutesContainer(ps.crunchMinutes.values)).flatMap { _ =>
          staffActor.ask(MinutesContainer(ps.staffMinutes.values))
        }
      }.foreach(_ => replyTo ! Ack)
  }

  override def askThenAck(message: Any, replyTo: ActorRef, actor: ActorRef): Unit = {
    actor.ask(message).foreach { _ =>
      message match {
        case flightsWithSplitsDiff@FlightsWithSplitsDiff(_, _) if flightsWithSplitsDiff.nonEmpty =>
          actor.ask(GetPortState(0L, Long.MaxValue)).mapTo[Option[FlightsWithSplits]].foreach {
            case None => sendStateToProbe()
            case Some(FlightsWithSplits(flights)) =>
              val updatedFlights: SortedMap[UniqueArrival, ApiFlightWithSplits] = SortedMap[UniqueArrival, ApiFlightWithSplits]() ++ flights
              state = state.copy(flights = updatedFlights)
              sendStateToProbe()
          }
        case mc: MinutesContainer[_, _] =>
          val minuteMillis = mc.minutes.map(_.minute)
          mc.minutes.headOption match {
            case None => sendStateToProbe()
            case Some(minuteLike) if minuteLike.toMinute.isInstanceOf[CrunchMinute] =>
              actor.ask(GetPortState(minuteMillis.min, minuteMillis.max)).mapTo[MinutesWithBookmarks[CrunchMinute, TQM]]
                .foreach { case MinutesWithBookmarks(container, _) =>
                  val updatedMinutes = state.crunchMinutes ++ container.minutes.map(ml => (ml.key, ml.toMinute))
                  state = state.copy(crunchMinutes = updatedMinutes)
                  sendStateToProbe()
                }
            case Some(minuteLike) if minuteLike.toMinute.isInstanceOf[StaffMinute] =>
              actor.ask(GetPortState(minuteMillis.min, minuteMillis.max)).mapTo[MinutesWithBookmarks[StaffMinute, TM]]
                .foreach { case MinutesWithBookmarks(container, _) =>
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
