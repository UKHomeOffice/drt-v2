package actors

import actors.acking.AckingReceiver.Ack
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.MilliTimes
import drt.shared.Terminals.T1
import org.specs2.execute.{Failure, Result}
import services.SDate
import services.crunch.CrunchTestLike
import services.crunch.deskrecs.GetFlights

import scala.concurrent.Await
import scala.concurrent.duration._

class FlightsStateActorResponsesSpec extends CrunchTestLike {
  def actor: ActorRef = system.actorOf(Props(new FlightsStateActor(None, 1, "flights", Map(), () => SDate.now(), MilliTimes.oneMinuteMillis)))

  val messagesAndResponseTypes: Map[Any, (PartialFunction[Any, Result], Any)] = Map(
    GetPortState(0L, 1L) -> ({ case Some(_: FlightsWithSplits) => success }, classOf[Some[FlightsWithSplits]]),
    GetFlightsForTerminal(0L, 1L, T1) -> (({ case _: FlightsWithSplits => success }, classOf[FlightsWithSplits])),
    GetUpdatesSince(0L, 0L, 1L) -> (({ case _: FlightsWithSplits => success }, classOf[FlightsWithSplits])),
    GetFlights(0L, 1L) -> (({ case _: FlightsWithSplits => success }, classOf[FlightsWithSplits])),
    FlightsWithSplitsDiff(List(), List()) -> (({ case Ack => success }, Ack))
    )

  def failed(expected: Any): PartialFunction[Any, Result] = {
    case u => Failure(s"Expected $expected. Got $u")
  }

  messagesAndResponseTypes.map {
    case (msg, (isSuccess, expectedType)) =>
      s"When I send it a ${msg.getClass} I should receive $expectedType" >> {
        Await.result(actor.ask(msg).map(isSuccess orElse failed(expectedType)), 1 second)
      }
  }
}
