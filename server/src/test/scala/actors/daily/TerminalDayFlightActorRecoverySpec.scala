package actors.daily

import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import akka.actor.{ActorRef, Props}
import akka.pattern.{StatusReply, ask}
import akka.persistence.PersistentActor
import controllers.ArrivalGenerator.flightWithSplitsForDayAndTerminal
import scalapb.GeneratedMessage
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals.FlightsWithSplits
import uk.gov.homeoffice.drt.ports.LiveFeedSource
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.FlightsWithSplitsDiffMessage
import uk.gov.homeoffice.drt.protobuf.serialisation.FlightMessageConversion
import uk.gov.homeoffice.drt.time.{SDate, SDateLike, UtcDate}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object PersistMessageForIdActor {
  def props(idToPersist: String): Props = Props(new PersistMessageForIdActor(idToPersist))
}

class PersistMessageForIdActor(idToPersist: String) extends PersistentActor {
  override def receiveRecover: Receive = {
    case _ => ()
  }

  override def receiveCommand: Receive = {
    case message: GeneratedMessage =>
      val replyTo = sender()
      persist(message)(message => {
        context.system.eventStream.publish(message)
        replyTo ! StatusReply.Ack
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

        val fws1 = flightWithSplitsForDayAndTerminal(recoveryPit, terminal, LiveFeedSource)
        val fws2 = flightWithSplitsForDayAndTerminal(recoveryPit.addHours(1), terminal, LiveFeedSource)

        val persistenceId = f"terminal-flights-${terminal.toString.toLowerCase}-${recoveryPit.getFullYear}-${recoveryPit.getMonth}%02d-${recoveryPit.getDate}%02d"

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
        val futureAck1 = persistingActor.ask(beforeRecoveryPointMessage)
        val futureAck2 = persistingActor.ask(afterRecoveryPointMessage)
        Await.ready(Future.sequence(List(futureAck1, futureAck2)), 1.second)

        val terminalDayFlightActorForPointInTime = actorForTerminalAndDatePit(terminal, recoveryPit.toUtcDate, recoveryPit)

        val state = Await.result(terminalDayFlightActorForPointInTime.ask(GetState).mapTo[FlightsWithSplits], 1.second)

        state.flights.keys must contain(fws1.unique)
        state.flights.keys must not(contain(fws2.unique))
      }
    }

    val cutOffThreshold = 1.second

    def actorForTerminalAndDate(terminal: Terminal, date: UtcDate): ActorRef = {
      system.actorOf(TerminalDayFlightActor.propsWithRemovalsCutoff(
        terminal, date, () => SDate(date), Some(cutOffThreshold), paxFeedSourceOrder, None, None, None))
    }

    "When I have a removal message that came through after the day for this actor " >> {
      "Then the removal message should be ignored" >> {
        val terminal: Terminal = T1
        val dateInQuestion: SDateLike = SDate("2021-01-01T00:00Z")

        val flightWithRemovalMessageOutsideThreshold = flightWithSplitsForDayAndTerminal(dateInQuestion.addHours(1), terminal, LiveFeedSource)
        val flightWithRemovalMessageOnDay = flightWithSplitsForDayAndTerminal(dateInQuestion.addHours(2), terminal, LiveFeedSource)

        val persistenceId = f"terminal-flights-${terminal.toString.toLowerCase}-${dateInQuestion.getFullYear}-${dateInQuestion.getMonth}%02d-${dateInQuestion.getDate}%02d"

        val onDayMessageWithFlight: FlightsWithSplitsDiffMessage = FlightsWithSplitsDiffMessage(
          Option(dateInQuestion.millisSinceEpoch),
          Seq(),
          Seq(
            FlightMessageConversion.flightWithSplitsToMessage(flightWithRemovalMessageOutsideThreshold),
            FlightMessageConversion.flightWithSplitsToMessage(flightWithRemovalMessageOnDay),
          )
        )

        val onDayMessageWithDeletion: FlightsWithSplitsDiffMessage = FlightsWithSplitsDiffMessage(
          Option(dateInQuestion.addHours(1).millisSinceEpoch),
          Seq(FlightMessageConversion.uniqueArrivalToMessage(flightWithRemovalMessageOnDay.unique)),
          Seq()
        )

        val nextDayMessageWithDeletion = FlightsWithSplitsDiffMessage(
          Option(dateInQuestion.addDays(1).addMillis(cutOffThreshold.toMillis).millisSinceEpoch),
          Seq(FlightMessageConversion.uniqueArrivalToMessage(flightWithRemovalMessageOutsideThreshold.unique)),
          Seq()
        )

        val persistingActor = system.actorOf(PersistMessageForIdActor.props(persistenceId))
        val futureAck1 = persistingActor.ask(onDayMessageWithFlight)
        val futureAck2 = persistingActor.ask(onDayMessageWithDeletion)
        val futureAck3 = persistingActor.ask(nextDayMessageWithDeletion)
        Await.ready(Future.sequence(List(futureAck1, futureAck2, futureAck3)), 1.second)

        val terminalDayFlightActor = actorForTerminalAndDate(terminal, dateInQuestion.toUtcDate)

        val state = Await.result(terminalDayFlightActor.ask(GetState).mapTo[FlightsWithSplits], 1.second)

        state.flights.keys must contain(flightWithRemovalMessageOutsideThreshold.unique)
        state.flights.keys must not(contain(flightWithRemovalMessageOnDay.unique))
      }
    }
  }

  def actorForTerminalAndDatePit(terminal: Terminal, date: UtcDate, pit: SDateLike): ActorRef =
    system.actorOf(TerminalDayFlightActor.propsPointInTime(terminal, date, () => SDate(date), pit.millisSinceEpoch, None, paxFeedSourceOrder, None))
}
