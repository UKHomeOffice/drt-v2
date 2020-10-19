package actors.daily

import actors.FlightMessageConversion
import actors.daily.TerminalDayFlightActor
import akka.actor.Props
import akka.persistence.PersistentActor
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{FlightsWithSplitsDiffMessage, FlightsWithSplitsMessage}

import actors.ArrivalGenerator.flightWithSplitsForDayAndTerminal
import actors.GetState
import actors.acking.AckingReceiver.Ack
import akka.actor.ActorRef
import akka.pattern.ask
import drt.shared.CrunchApi.CrunchMinute
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Queues.{EeaDesk, Queue}
import drt.shared.Terminals.{T1, T2, Terminal}
import drt.shared.{SDateLike, TQM, UtcDate}
import services.SDate
import services.crunch.CrunchTestLike

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object PersistMessageForIdActor {
  def props(idToPersist: String) = Props(new PersistMessageForIdActor(idToPersist))
}

class PersistMessageForIdActor(idToPersist: String) extends PersistentActor {
  override def receiveRecover: Receive = {
    case _ => Unit
  }

  override def receiveCommand: Receive = {
    case message: GeneratedMessage =>
      val replyTo = sender()
      persist(message)((message) => {
        context.system.eventStream.publish(message)
        replyTo ! Ack
      })

  }

  override def persistenceId: String = idToPersist
}


class TerminalDayFlightActorRecoverySpec extends CrunchTestLike {

  "Given a TerminalDayFlightActor that has persisted state" >> {
    "When I restore to a point in time " >> {
      "Then diffs created after that point in time should be ignored" >> {
        val terminal: Terminal = T1
        val recoveryPit: SDateLike = SDate("2020-01-01T12:00Z")

        val fws1 = flightWithSplitsForDayAndTerminal(recoveryPit, terminal)
        val fws2 = flightWithSplitsForDayAndTerminal(recoveryPit.addHours(1), terminal)

        val persistenceId = f"terminal-flights-${terminal.toString.toLowerCase}-${recoveryPit.getFullYear()}-${recoveryPit.getMonth()}%02d-${recoveryPit.getDate()}%02d"

        val beforeRecoveryPointMessage = FlightsWithSplitsDiffMessage(
          Option(recoveryPit.addHours(-1).millisSinceEpoch),
          Seq(),
          Seq(FlightMessageConversion.flightWithSplitsToMessage(fws1))
        )

        val afterRecoveryPointMessage = FlightsWithSplitsDiffMessage(
          Option(recoveryPit.addHours(1).millisSinceEpoch),
          Seq(),
          Seq(FlightMessageConversion.flightWithSplitsToMessage(fws2))
        )

        val persistingActor = system.actorOf(PersistMessageForIdActor.props(persistenceId))
        val futureAck1 =  persistingActor.ask(beforeRecoveryPointMessage)
        val futureAck2 =  persistingActor.ask(afterRecoveryPointMessage)
        Await.ready(Future.sequence(List(futureAck1, futureAck2)), 1 second)

        val terminalDayFlightActorForPointInTime = actorForTerminalAndDatePit(terminal, recoveryPit.toUtcDate, recoveryPit)

        val state = Await.result(terminalDayFlightActorForPointInTime.ask(GetState).mapTo[FlightsWithSplits], 1 second)

        state.flights.keys must(contain((fws1.unique)))
        state.flights.keys must(not(contain(fws2.unique)))
      }
    }
  }

  private def actorForTerminalAndDatePit(terminal: Terminal, date: UtcDate, pit: SDateLike): ActorRef = {
    system.actorOf(TerminalDayFlightActor.propsPointInTime(terminal, date, () => SDate(date), pit.millisSinceEpoch))
  }
}
