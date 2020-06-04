package services.crunch

import actors._
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import controllers.ArrivalGenerator
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Queues.EeaDesk
import drt.shared.Terminals.{T1, Terminal}
import drt.shared._
import services.SDate
import services.crunch.deskrecs.GetFlights
import services.graphstages.Crunch

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class PortStateRequestsSpec extends CrunchTestLike {
  "Given an empty PartitionedPortState" >> {
    val scheduled = "2020-01-01T00:00"
    val now = () => SDate(scheduled)
    val expireAfterMillis = MilliTimes.oneDayMillis
    val forecastMaxDays = 10
    val forecastMaxMillis: () => MillisSinceEpoch = () => now().addDays(forecastMaxDays).millisSinceEpoch
    val liveCsa = system.actorOf(Props(new CrunchStateActor(None, Sizes.oneMegaByte, "crunch-state", defaultAirportConfig.queuesByTerminal, now, expireAfterMillis, false, forecastMaxMillis)))
    val fcstCsa = system.actorOf(Props(new CrunchStateActor(None, Sizes.oneMegaByte, "forecast-crunch-state", defaultAirportConfig.queuesByTerminal, now, expireAfterMillis, false, forecastMaxMillis)))
    val ps = PortStateActor(now, liveCsa, fcstCsa, defaultAirportConfig.queuesByTerminal)

    "When I send it a flight and then ask for its flights" >> {
      val fws = flightsWithSplits(List(("BA1000", scheduled, T1)))
      val eventualAck = ps.ask(FlightsWithSplitsDiff(fws, List()))

      "Then I should see the flight I sent it" >> {
        val result = Await.result(eventualFlights(eventualAck, now, ps), 1 second)

        result === FlightsWithSplits(flightsToMap(now, fws))
      }
    }

    "When I send it a flight and then ask for updates since just before now" >> {
      val fws = flightsWithSplits(List(("BA1000", scheduled, T1)))
      val eventualAck = ps.ask(FlightsWithSplitsDiff(fws, List()))

      "Then I should see the flight I sent it in the port state" >> {
        val sinceMillis = now().addMinutes(-1).millisSinceEpoch
        val result = Await.result(eventualPortStateUpdates(eventualAck, now, ps, sinceMillis), 1 second)

        result === Option(PortStateUpdates(now().millisSinceEpoch, setUpdatedFlights(fws, now().millisSinceEpoch).toSet, Set(), Set()))
      }
    }

    def flightsWithSplits(params: Iterable[(String, String, Terminal)]): List[ApiFlightWithSplits] = params.map { case (_, scheduled, _) =>
      val flight = ArrivalGenerator.arrival("BA1000", schDt = scheduled, terminal = T1)
      ApiFlightWithSplits(flight, Set())
    }.toList

    "When I send it 2 flights consecutively and then ask for its flights" >> {
      val scheduled2 = "2020-01-01T00:25"
      val fws1 = flightsWithSplits(List(("BA1000", scheduled, T1)))
      val fws2 = flightsWithSplits(List(("FR5000", scheduled2, T1)))
      val eventualAck = ps.ask(FlightsWithSplitsDiff(fws1, List())).flatMap(_ => ps.ask(FlightsWithSplitsDiff(fws2, List())))

      "Then I should see both flights I sent it" >> {
        val result = Await.result(eventualFlights(eventualAck, now, ps), 1 second)

        result === FlightsWithSplits(flightsToMap(now, fws1 ++ fws2))
      }
    }

    "When I send it a DeskRecMinute and then ask for its crunch minutes" >> {
      val lm = DeskRecMinute(T1, EeaDesk, now().millisSinceEpoch, 1, 2, 3, 4)

      val eventualAck = ps.ask(DeskRecMinutes(Seq(lm)))

      "Then I should a matching crunch minute" >> {
        val result = Await.result(eventualPortState(eventualAck, now, ps), 1 second)
        val expectedCm = CrunchMinute(T1, EeaDesk, now().millisSinceEpoch, 1, 2, 3, 4)

        result === Option(PortState(Seq(), setUpdatedCms(Seq(expectedCm), now().millisSinceEpoch), Seq()))
      }
    }

    "When I send it a DeskRecMinute and then ask for updates since just before now" >> {
      val lm = DeskRecMinute(T1, EeaDesk, now().millisSinceEpoch, 1, 2, 3, 4)

      val eventualAck = ps.ask(DeskRecMinutes(Seq(lm)))

      "Then I should the matching crunch minute in the port state" >> {
        val sinceMillis = now().addMinutes(-1).millisSinceEpoch
        val result = Await.result(eventualPortStateUpdates(eventualAck, now, ps, sinceMillis), 1 second)
        val expectedCm = CrunchMinute(T1, EeaDesk, now().millisSinceEpoch, 1, 2, 3, 4)

        result === Option(PortStateUpdates(now().millisSinceEpoch, Set(), setUpdatedCms(Seq(expectedCm), now().millisSinceEpoch).toSet, Set()))
      }
    }

    "When I send it two DeskRecMinutes consecutively and then ask for its crunch minutes" >> {
      val lm1 = DeskRecMinute(T1, EeaDesk, now().millisSinceEpoch, 1, 2, 3, 4)
      val lm2 = DeskRecMinute(T1, EeaDesk, now().addMinutes(1).millisSinceEpoch, 2, 3, 4, 5)

      val eventualAck = ps.ask(DeskRecMinutes(Seq(lm1))).flatMap( _ => ps.ask(DeskRecMinutes(Seq(lm2))))

      "Then I should a matching crunch minute" >> {
        val result = Await.result(eventualPortState(eventualAck, now, ps), 1 second)
        val expectedCms = Seq(
          CrunchMinute(T1, EeaDesk, now().millisSinceEpoch, 1, 2, 3, 4),
          CrunchMinute(T1, EeaDesk, now().addMinutes(1).millisSinceEpoch, 2, 3, 4, 5))

        result === Option(PortState(Seq(), setUpdatedCms(expectedCms, now().millisSinceEpoch), Seq()))
      }
    }

    "When I send it a StaffMinute and then ask for its staff minutes" >> {
      val sm = StaffMinute(T1, now().millisSinceEpoch, 1, 2, 3)

      val eventualAck = ps.ask(StaffMinutes(Seq(sm)))

      "Then I should a matching staff minute" >> {
        val result = Await.result(eventualPortState(eventualAck, now, ps), 1 second)

        result === Option(PortState(Seq(), Seq(), setUpdatedSms(Seq(sm), now().millisSinceEpoch)))
      }
    }

    "When I send it two StaffMinutes consecutively and then ask for its staff minutes" >> {
      val sm1 = StaffMinute(T1, now().millisSinceEpoch, 1, 2, 3)
      val sm2 = StaffMinute(T1, now().addMinutes(1).millisSinceEpoch, 1, 2, 3)

      val eventualAck = ps.ask(StaffMinutes(Seq(sm1))).flatMap(_ => ps.ask(StaffMinutes(Seq(sm2))))

      "Then I should a matching staff minute" >> {
        val result = Await.result(eventualPortState(eventualAck, now, ps), 1 second)

        result === Option(PortState(Seq(), Seq(), setUpdatedSms(Seq(sm1, sm2), now().millisSinceEpoch)))
      }
    }
  }

  def setUpdatedFlights(fws: Iterable[ApiFlightWithSplits], updatedMillis: MillisSinceEpoch): Seq[ApiFlightWithSplits] =
    fws.map(_.copy(lastUpdated = Option(updatedMillis))).toSeq

  def setUpdatedCms(cms: Iterable[CrunchMinute], updatedMillis: MillisSinceEpoch): Seq[CrunchMinute] =
    cms.map(_.copy(lastUpdated = Option(updatedMillis))).toSeq

  def setUpdatedSms(sms: Iterable[StaffMinute], updatedMillis: MillisSinceEpoch): Seq[StaffMinute] =
    sms.map(_.copy(lastUpdated = Option(updatedMillis))).toSeq

  def eventualFlights(eventualAck: Future[Any], now: () => SDateLike, ps: ActorRef): Future[FlightsWithSplits] = eventualAck.flatMap { _ =>
    val startMillis = now().getLocalLastMidnight.millisSinceEpoch
    val endMillis = now().getLocalNextMidnight.millisSinceEpoch
    ps.ask(GetFlights(startMillis, endMillis)).mapTo[FlightsWithSplits]
  }

  def eventualPortState(eventualAck: Future[Any], now: () => SDateLike, ps: ActorRef): Future[Option[PortState]] = eventualAck.flatMap { _ =>
    val startMillis = now().getLocalLastMidnight.millisSinceEpoch
    val endMillis = now().getLocalNextMidnight.millisSinceEpoch
    ps.ask(GetPortState(startMillis, endMillis)).mapTo[Option[PortState]]
  }

  def eventualPortStateUpdates(eventualAck: Future[Any], now: () => SDateLike, ps: ActorRef, sinceMillis: MillisSinceEpoch): Future[Option[PortState]] = eventualAck.flatMap { _ =>
    val startMillis = now().getLocalLastMidnight.millisSinceEpoch
    val endMillis = now().getLocalNextMidnight.millisSinceEpoch
    ps.ask(GetUpdatesSince(sinceMillis, startMillis, endMillis)).mapTo[Option[PortState]]
  }

  def flightsToMap(now: () => SDateLike, flights: Seq[ApiFlightWithSplits]): Map[UniqueArrival, ApiFlightWithSplits] = flights.map { fws1 =>
    fws1.unique -> fws1.copy(lastUpdated = Option(now().millisSinceEpoch))
  }.toMap
}
