package services

import akka.NotUsed
import akka.actor.{ActorRef, Props}
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import akka.util.Timeout
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.{DeskRecMinute, DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.EeaDesk
import drt.shared.Terminals.{T1, Terminal}
import drt.shared._
import services.crunch.CrunchTestLike
import services.crunch.desklimits.{PortDeskLimits, TerminalDeskLimitsLike}
import services.crunch.deskrecs.{DesksAndWaitsPortProviderLike, MockPortStateActor, RunnableDeskRecs, SetFlights}
import services.graphstages.Crunch.LoadMinute
import services.graphstages.{Buffer, CrunchMocks}

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.duration._

case class MockDesksAndWaitsPort(minutesToCrunch: Int, crunchOffsetMinutes: Int) extends DesksAndWaitsPortProviderLike {
  override def flightsToLoads(flights: FlightsWithSplits,
                              crunchStartMillis: MillisSinceEpoch): Map[TQM, LoadMinute] =
    Seq(LoadMinute(T1, Queues.EeaDesk, crunchStartMillis, 0, 0)).map(lm => (lm.uniqueId, lm)).toMap

  override def loadsToDesks(minuteMillis: NumericRange[MillisSinceEpoch],
                            loads: Map[TQM, LoadMinute],
                            maxDesksByTerminal: Map[Terminal, TerminalDeskLimitsLike]
                           ): DeskRecMinutes = DeskRecMinutes(Seq(DeskRecMinute(T1, EeaDesk, minuteMillis(0), 1, 1, 1, 1)))
}

class StreamingWorkloadSpec extends CrunchTestLike {
  implicit val timeout: Timeout = new Timeout(10 seconds)

  val mockCrunch: TryCrunch = CrunchMocks.mockCrunch
  val noDelay: Long = 0L
  val smallDelay: Long = 66L

  val portStateProbe: TestProbe = TestProbe("port-state")

  def newBuffer: Buffer = Buffer(Iterable())

  val mockPortStateActor: ActorRef = system.actorOf(Props(new MockPortStateActor(portStateProbe, smallDelay)))
  mockPortStateActor ! SetFlights(List(ApiFlightWithSplits(ArrivalGenerator.arrival(terminal = T1, origin = PortCode("JFK"), actPax = Option(100)), Set())))
  val portDeskRecs: MockDesksAndWaitsPort = MockDesksAndWaitsPort(1440, defaultAirportConfig.crunchOffsetMinutes)
  val maxDesksProvider: Map[Terminal, TerminalDeskLimitsLike] = PortDeskLimits.flexed(defaultAirportConfig)
  val (millisToCrunchSourceActor: ActorRef, _) = RunnableDeskRecs(mockPortStateActor, portDeskRecs, newBuffer, maxDesksProvider).run()

  val askableSource: AskableActorRef = millisToCrunchSourceActor

  var days = List(List(0, 1, 2, 3, 4, 5, 6, 7), List(0))

  def nextDays: List[Int] = days match {
    case Nil => List()
    case head :: tail =>
      days = tail
      head
  }

  val midnight20190101: SDateLike = SDate("2019-01-01T00:00")

  "Given 11 days to crunch, with a mock request delay of 75ms, and days being added at a rate of 50ms " +
    "When I look at the crunched days " >> {

    Source.tick(0 milliseconds, 120 milliseconds, NotUsed)
      .map { _ => askableSource ? nextDays.map(d => midnight20190101.addDays(d).millisSinceEpoch) }
      .runWith(Sink.seq)

    val totalDaysToCrunch = days.flatten.length
    val crunchedDaysInSequence: Seq[String] = portStateProbe.receiveN(totalDaysToCrunch * 2).collect {
      case DeskRecMinutes(drms) => drms.map(drm => SDate(drm.minute).toISODateOnly)
    }.flatten

    s"There should be $totalDaysToCrunch days crunched" >> {
      crunchedDaysInSequence.length === totalDaysToCrunch
    }

    "I should see 2019-01-01 crunched twice" >> {
      crunchedDaysInSequence.count(_ == "2019-01-01") === 2
    }

    "The 2nd 2019-01-01 should not be at the end of the list, ie it should have shuffled to the front of the queue when it was added" >> {
      crunchedDaysInSequence.takeRight(1).head != "2019-01-01"
    }
  }
}
