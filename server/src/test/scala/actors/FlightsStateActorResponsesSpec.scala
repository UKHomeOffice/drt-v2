package actors

import actors.acking.AckingReceiver.Ack
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Terminals.{T1, T2}
import drt.shared.{ApiFlightWithSplits, MilliTimes, SDateLike}
import org.specs2.execute.{Failure, Result}
import services.SDate
import services.crunch.CrunchTestLike
import services.crunch.deskrecs.{GetStateForDateRange, GetStateForTerminalDateRange}

import scala.concurrent.Await
import scala.concurrent.duration._

class FlightsStateActorResponsesSpec extends CrunchTestLike {
  val myToday = "2020-06-01"
  val myNow: () => SDateLike = () => SDate(myToday)

  val scheduledBeforeExpiry = "2020-05-25T12:00"
  val scheduled0506 = "2020-06-05T12:00"
  val scheduled0606 = "2020-06-06T12:00"

  val legacyDataCutoff: SDateLike = SDate("1970-01-01")

  def actor: ActorRef = system.actorOf(Props(new FlightsStateActor(myNow, MilliTimes.oneDayMillis, Map(), legacyDataCutoff, 1000)))

  val messagesAndResponseTypes: Map[Any, (PartialFunction[Any, Result], Any)] = Map(
    GetStateForTerminalDateRange(0L, 1L, T1) -> (({ case _: FlightsWithSplits => success }, classOf[FlightsWithSplits])),
    GetUpdatesSince(0L, 0L, 1L) -> (({ case _: FlightsWithSplits => success }, classOf[FlightsWithSplits])),
    GetStateForDateRange(0L, 1L) -> (({ case _: FlightsWithSplits => success }, classOf[FlightsWithSplits])),
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

  "Given a new actor" >> {
    val flightsActor = actor

    val startMillis = SDate(scheduled0606).getLocalLastMidnight.millisSinceEpoch
    val endMillis = SDate(scheduled0606).getLocalNextMidnight.millisSinceEpoch

    s"When I send it a flight scheduled for $scheduled0606 at T1" >> {
      val fws06 = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = scheduled0606, terminal = T1), Set())
      val fws = List(fws06)
      Await.ready(flightsActor.ask(FlightsWithSplitsDiff(fws, List())), 1 second)

      "When I ask for all flights between 2020-06-06 and 2020-06-07" >> {
        val flights = Await.result(flightsActor.ask(GetStateForDateRange(startMillis, endMillis)), 1 second).asInstanceOf[FlightsWithSplits]
        "I should get the flight I sent it" >> {
          flights.flights.toMap.keys === Set(fws06.unique)
        }
      }

      "When I ask for T1 flights between 2020-06-06 and 2020-06-07" >> {
        val flights = Await.result(flightsActor.ask(GetStateForTerminalDateRange(startMillis, endMillis, T1)), 1 second).asInstanceOf[FlightsWithSplits]
        "I should get the flight I sent it" >> {
          flights.flights.toMap.keys === Set(fws06.unique)
        }
      }

      "When I ask for T2 flights between 2020-06-06 and 2020-06-07" >> {
        val flights = Await.result(flightsActor.ask(GetStateForTerminalDateRange(startMillis, endMillis, T2)), 1 second).asInstanceOf[FlightsWithSplits]
        "I should get no flights" >> {
          flights.flights.isEmpty
        }
      }

      "When I send a new flight with an updated time of now, and then ask for updates since a second before now" >> {
        val fwsUpdatedNow = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = scheduled0606, terminal = T1), Set())
          .copy(lastUpdated = Option(myNow().millisSinceEpoch))
        Await.ready(flightsActor.ask(FlightsWithSplitsDiff(List(fwsUpdatedNow), List())), 1 second)

        val sinceAMinuteAgo = myNow().addMillis(-1000).millisSinceEpoch
        val flights = Await.result(flightsActor.ask(GetUpdatesSince(sinceAMinuteAgo, startMillis, endMillis)), 1 second).asInstanceOf[FlightsWithSplits]
        "Then I should get just the flight with a new updated time" >> {
          flights.flights.toMap.keys === Set(fwsUpdatedNow.unique)
        }
      }
    }

    s"When I send it a diff with two flights, scheduled for $scheduled0506 & $scheduled0606 at T1" >> {
      val fws05 = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = scheduled0506, terminal = T1), Set())
      val fws06 = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = scheduled0606, terminal = T1), Set())
      val fws = List(fws05, fws06)
      Await.ready(flightsActor.ask(FlightsWithSplitsDiff(fws, List())), 1 second)

      "When I ask for all flights between 2020-06-06 and 2020-06-07" >> {
        val startMillis = SDate(scheduled0606).getLocalLastMidnight.millisSinceEpoch
        val endMillis = SDate(scheduled0606).getLocalNextMidnight.millisSinceEpoch
        val flights = Await.result(flightsActor.ask(GetStateForDateRange(startMillis, endMillis)), 1 second).asInstanceOf[FlightsWithSplits]
        "I should get just the flight scheduled 0606 I sent it" >> {
          flights.flights.toMap.keys === Set(fws06.unique)
        }
      }
    }

    s"When I send it a diff with two flights, one scheduled before expiry ($scheduledBeforeExpiry) and one in the future $scheduled0606 at T1" >> {
      val fwsBeforeExpiry = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = scheduledBeforeExpiry, terminal = T1), Set())
      val fws06 = ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = scheduled0606, terminal = T1), Set())
      val fws = List(fwsBeforeExpiry, fws06)
      Await.ready(flightsActor.ask(FlightsWithSplitsDiff(fws, List())), 1 second)

      "When I ask for all flights between 2020-05-25 and 2020-06-07" >> {
        val startMillis = SDate(scheduledBeforeExpiry).getLocalLastMidnight.millisSinceEpoch
        val endMillis = SDate(scheduled0606).getLocalNextMidnight.millisSinceEpoch
        val flights = Await.result(flightsActor.ask(GetStateForDateRange(startMillis, endMillis)), 1 second).asInstanceOf[FlightsWithSplits]
        "I should get just the flight scheduled 0606 I sent it as the other should have been purged from the state" >> {
          flights.flights.toMap.keys === Set(fws06.unique)
        }
      }
    }
  }
}
