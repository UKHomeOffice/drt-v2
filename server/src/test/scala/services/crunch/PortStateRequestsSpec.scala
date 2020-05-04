package services.crunch

import actors._
import actors.daily.{LevelDbConfig, StartUpdatesStream}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.TestKit
import akka.util.Timeout
import controllers.ArrivalGenerator
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Queues.EeaDesk
import drt.shared.Terminals.{T1, Terminal}
import drt.shared._
import org.specs2.mutable.{Specification, SpecificationLike}
import org.specs2.specification.BeforeEach
import services.SDate
import services.crunch.deskrecs.GetFlights
import test.TestActors.{ResetData, TestTerminalDayQueuesActor}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}

//FlightsWithSplits(Map(
// UniqueArrival(1000,T1,1577836800000) -> ApiFlightWithSplits(Arrival(None,BA,1000,None,,None,None,None,None,None,None,None,None,None,None,None,,T1,,1577836800000,Some(1577836800000),Set(),None,None),Set(),Some(1577836800000)),
// UniqueArrival(1000,T1,1577838300000) -> ApiFlightWithSplits(Arrival(None,BA,1000,None,,None,None,None,None,None,None,None,None,None,None,None,,T1,,1577838300000,Some(1577838300000),Set(),None,None),Set(),Some(1577836800000)))) !=
//FlightsWithSplits(Map(
// UniqueArrival(1000,T1,1577836800000) -> ApiFlightWithSplits(Arrival(None,BA,1000,None,,None,None,None,None,None,None,None,None,None,None,None,,T1,,1577836800000,Some(1577836800000),Set(),None,None),Set(),Some(1577836800000))))

class PortStateRequestsSpec extends TestKit(ActorSystem("drt", LevelDbConfig.config("PortStateRequestsSpec"))) with SpecificationLike with BeforeEach {
  override def before: Unit = {
    println(s"Deleting data")
    println(LevelDbConfig.clearData("PortStateRequestsSpec"))
  }

  implicit val timeout: Timeout = new Timeout(1 second)

  implicit val ec: ExecutionContextExecutor = ExecutionContext.global

  val airportConfig: AirportConfig = TestDefaults.airportConfig
  val scheduled = "2020-01-01T00:00"
  val myNow: () => SDateLike = () => SDate(scheduled)
  val expireAfterMillis: Int = MilliTimes.oneDayMillis
  val forecastMaxDays = 10
  val forecastMaxMillis: () => MillisSinceEpoch = () => myNow().addDays(forecastMaxDays).millisSinceEpoch

  def portStateActorProvider: () => ActorRef = () => {
    val liveCsa = system.actorOf(Props(new CrunchStateActor(None, Sizes.oneMegaByte, "crunch-state", airportConfig.queuesByTerminal, myNow, expireAfterMillis, false, forecastMaxMillis)))
    val fcstCsa = system.actorOf(Props(new CrunchStateActor(None, Sizes.oneMegaByte, "forecast-crunch-state", airportConfig.queuesByTerminal, myNow, expireAfterMillis, false, forecastMaxMillis)))
    PortStateActor(myNow, liveCsa, fcstCsa)
  }

  def partitionedPortStateActorProvider: () => ActorRef = () => PartitionedPortStateActor(myNow, airportConfig, TestStreamingJournal)

  def resetData(terminal: Terminal, day: SDateLike): Unit = {
    val actor = system.actorOf(Props(new TestTerminalDayQueuesActor(day.getFullYear(), day.getMonth(), day.getDate(), terminal, () => SDate.now())))
    Await.ready(actor.ask(ResetData), 1 second)
    println(s"\n\nData reset")
  }

  private val actorProviders = List(
    ("PortStateActor", portStateActorProvider),
    ("PartitionedPortStateActor", partitionedPortStateActorProvider))

  def flightsWithSplits(params: Iterable[(String, String, Terminal)]): List[ApiFlightWithSplits] = params.map { case (_, scheduled, _) =>
    val flight = ArrivalGenerator.arrival("BA1000", schDt = scheduled, terminal = T1)
    ApiFlightWithSplits(flight, Set())
  }.toList

  actorProviders.map {
    case (name, actorProvider) =>
      s"Given an empty $name" >> {
        val ps = actorProvider()

        resetData(T1, myNow())

        "When I send it a flight and then ask for its flights" >> {
          val fws = flightsWithSplits(List(("BA1000", scheduled, T1)))
          val eventualAck = ps.ask(FlightsWithSplitsDiff(fws, List()))

          "Then I should see the flight I sent it" >> {
            val result = Await.result(eventualFlights(eventualAck, myNow, ps), 1 second)

            result === FlightsWithSplits(flightsToMap(myNow, fws))
          }
        }

        "When I send it a flight and then ask for updates since just before now" >> {
          val fws = flightsWithSplits(List(("BA1000", scheduled, T1)))
          val eventualAck = ps.ask(FlightsWithSplitsDiff(fws, List()))

          "Then I should see the flight I sent it in the port state" >> {
            val sinceMillis = myNow().addMinutes(-1).millisSinceEpoch
            val result = Await.result(eventualPortStateUpdates(eventualAck, myNow, ps, sinceMillis), 1 second)

            result === Option(PortStateUpdates(myNow().millisSinceEpoch, setUpdatedFlights(fws, myNow().millisSinceEpoch).toSet, Set(), Set()))
          }
        }

        "When I send it 2 flights consecutively and then ask for its flights" >> {
          val scheduled2 = "2020-01-01T00:25"
          val fws1 = flightsWithSplits(List(("BA1000", scheduled, T1)))
          val fws2 = flightsWithSplits(List(("FR5000", scheduled2, T1)))
          val eventualAck = ps.ask(FlightsWithSplitsDiff(fws1, List())).flatMap(_ => ps.ask(FlightsWithSplitsDiff(fws2, List())))

          "Then I should see both flights I sent it" >> {
            val result = Await.result(eventualFlights(eventualAck, myNow, ps), 1 second)

            result === FlightsWithSplits(flightsToMap(myNow, fws1 ++ fws2))
          }
        }
//
//        "When I send it a DeskRecMinute and then ask for its crunch minutes" >> {
//          val lm = DeskRecMinute(T1, EeaDesk, myNow().millisSinceEpoch, 1, 2, 3, 4)
//
//          val eventualAck = ps.ask(DeskRecMinutes(Seq(lm)))
//
//          "Then I should a matching crunch minute" >> {
//            val result = Await.result(eventualPortState(eventualAck, myNow, ps), 1 second)
//            val expectedCm = CrunchMinute(T1, EeaDesk, myNow().millisSinceEpoch, 1, 2, 3, 4)
//
//            result === Option(PortState(Seq(), setUpdatedCms(Seq(expectedCm), myNow().millisSinceEpoch), Seq()))
//          }
//        }
//
//        "When I send it a DeskRecMinute and then ask for updates since just before now" >> {
//          val lm = DeskRecMinute(T1, EeaDesk, myNow().millisSinceEpoch, 1, 2, 3, 4)
//
//          //          resetData(T1, myNow().getUtcLastMidnight)
//          val eventualAck = ps.ask(StartUpdatesStream(T1, myNow(), 0L))
//            .flatMap { _ =>
//              ps.ask(DeskRecMinutes(Seq(lm)))
//            }
//
//          "Then I should the matching crunch minute in the port state" >> {
//            val sinceMillis = myNow().addMinutes(-1).millisSinceEpoch
//            val result = Await.result(eventualPortStateUpdates(eventualAck, myNow, ps, sinceMillis), 1 second)
//            val expectedCm = CrunchMinute(T1, EeaDesk, myNow().millisSinceEpoch, 1, 2, 3, 4)
//
//            result === Option(PortStateUpdates(myNow().millisSinceEpoch, Set(), setUpdatedCms(Seq(expectedCm), myNow().millisSinceEpoch).toSet, Set()))
//          }
//        }
//
//        "When I send it two DeskRecMinutes consecutively and then ask for its crunch minutes" >> {
//          val lm1 = DeskRecMinute(T1, EeaDesk, myNow().millisSinceEpoch, 1, 2, 3, 4)
//          val lm2 = DeskRecMinute(T1, EeaDesk, myNow().addMinutes(1).millisSinceEpoch, 2, 3, 4, 5)
//
//          val eventualAck = ps.ask(DeskRecMinutes(Seq(lm1))).flatMap(_ => ps.ask(DeskRecMinutes(Seq(lm2))))
//
//          "Then I should a matching crunch minute" >> {
//            val result = Await.result(eventualPortState(eventualAck, myNow, ps), 1 second)
//            val expectedCms = Seq(
//              CrunchMinute(T1, EeaDesk, myNow().millisSinceEpoch, 1, 2, 3, 4),
//              CrunchMinute(T1, EeaDesk, myNow().addMinutes(1).millisSinceEpoch, 2, 3, 4, 5))
//
//            result === Option(PortState(Seq(), setUpdatedCms(expectedCms, myNow().millisSinceEpoch), Seq()))
//          }
//        }
//
//        "When I send it a StaffMinute and then ask for its staff minutes" >> {
//          val sm = StaffMinute(T1, myNow().millisSinceEpoch, 1, 2, 3)
//
//          val eventualAck = ps.ask(StaffMinutes(Seq(sm)))
//
//          "Then I should a matching staff minute" >> {
//            val result = Await.result(eventualPortState(eventualAck, myNow, ps), 1 second)
//
//            result === Option(PortState(Seq(), Seq(), setUpdatedSms(Seq(sm), myNow().millisSinceEpoch)))
//          }
//        }
//
//        "When I send it two StaffMinutes consecutively and then ask for its staff minutes" >> {
//          val sm1 = StaffMinute(T1, myNow().millisSinceEpoch, 1, 2, 3)
//          val sm2 = StaffMinute(T1, myNow().addMinutes(1).millisSinceEpoch, 1, 2, 3)
//
//          val eventualAck = ps.ask(StaffMinutes(Seq(sm1))).flatMap(_ => ps.ask(StaffMinutes(Seq(sm2))))
//
//          "Then I should a matching staff minute" >> {
//            val result = Await.result(eventualPortState(eventualAck, myNow, ps), 1 second)
//
//            result === Option(PortState(Seq(), Seq(), setUpdatedSms(Seq(sm1, sm2), myNow().millisSinceEpoch)))
//          }
//        }
//
//        "When I send it a flight, a queue & a staff minute, and ask for the terminal state" >> {
//          val fws = flightsWithSplits(List(("BA1000", scheduled, T1)))
//          val drm = DeskRecMinute(T1, EeaDesk, myNow().millisSinceEpoch, 1, 2, 3, 4)
//          val sm1 = StaffMinute(T1, myNow().millisSinceEpoch, 1, 2, 3)
//
//          val eventualAck1 = ps.ask(StaffMinutes(Seq(sm1)))
//          val eventualAck2 = ps.ask(DeskRecMinutes(Seq(drm)))
//          val eventualAck3 = ps.ask(FlightsWithSplitsDiff(fws, List()))
//          val eventualAck = Future.sequence(Seq(eventualAck1, eventualAck2, eventualAck3))
//
//          "Then I should see the flight, & corresponding crunch & staff minutes" >> {
//            val result = Await.result(eventualTerminalState(eventualAck, myNow, ps), 1 second)
//
//            val expectedCm = CrunchMinute(T1, EeaDesk, myNow().millisSinceEpoch, 1, 2, 3, 4)
//
//            result === Option(PortState(setUpdatedFlights(fws, myNow().millisSinceEpoch), setUpdatedCms(Seq(expectedCm), myNow().millisSinceEpoch), setUpdatedSms(Seq(sm1), myNow().millisSinceEpoch)))
//          }
//        }
      }
  }

  def setUpdatedFlights(fws: Iterable[ApiFlightWithSplits], updatedMillis: MillisSinceEpoch): Seq[ApiFlightWithSplits] =
    fws.map(_.copy(lastUpdated = Option(updatedMillis))).toSeq

  def setUpdatedCms(cms: Iterable[CrunchMinute], updatedMillis: MillisSinceEpoch): Seq[CrunchMinute] =
    cms.map(_.copy(lastUpdated = Option(updatedMillis))).toSeq

  def setUpdatedSms(sms: Iterable[StaffMinute], updatedMillis: MillisSinceEpoch): Seq[StaffMinute] =
    sms.map(_.copy(lastUpdated = Option(updatedMillis))).toSeq

  def eventualFlights(eventualAck: Future[Any],
                      now: () => SDateLike,
                      ps: ActorRef): Future[FlightsWithSplits] = eventualAck.flatMap { _ =>
    val startMillis = now().getLocalLastMidnight.millisSinceEpoch
    val endMillis = now().getLocalNextMidnight.millisSinceEpoch
    ps.ask(GetFlights(startMillis, endMillis)).mapTo[FlightsWithSplits]
  }

  def eventualPortState(eventualAck: Future[Any],
                        now: () => SDateLike,
                        ps: ActorRef): Future[Option[PortState]] = eventualAck.flatMap { _ =>
    val startMillis = now().getLocalLastMidnight.millisSinceEpoch
    val endMillis = now().getLocalNextMidnight.millisSinceEpoch
    ps.ask(GetPortState(startMillis, endMillis)).mapTo[Option[PortState]]
  }

  def eventualTerminalState(eventualAck: Future[Any],
                            now: () => SDateLike,
                            ps: ActorRef): Future[Option[PortState]] = eventualAck.flatMap { _ =>
    val startMillis = now().getLocalLastMidnight.millisSinceEpoch
    val endMillis = now().getLocalNextMidnight.millisSinceEpoch
    ps.ask(GetPortStateForTerminal(startMillis, endMillis, T1)).mapTo[Option[PortState]]
  }

  def eventualPortStateUpdates(eventualAck: Future[Any],
                               now: () => SDateLike,
                               ps: ActorRef,
                               sinceMillis: MillisSinceEpoch): Future[Option[PortState]] = eventualAck.flatMap { _ =>
    val startMillis = now().getLocalLastMidnight.millisSinceEpoch
    val endMillis = now().getLocalNextMidnight.millisSinceEpoch
    ps.ask(GetUpdatesSince(sinceMillis, startMillis, endMillis)).mapTo[Option[PortState]]
  }

  def flightsToMap(now: () => SDateLike,
                   flights: Seq[ApiFlightWithSplits]): Map[UniqueArrival, ApiFlightWithSplits] = flights.map { fws1 =>
    fws1.unique -> fws1.copy(lastUpdated = Option(now().millisSinceEpoch))
  }.toMap
}
