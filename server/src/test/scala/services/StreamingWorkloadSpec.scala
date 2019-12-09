package services

import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import akka.util.Timeout
import drt.shared.CrunchApi.{DeskRecMinute, DeskRecMinutes, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues
import drt.shared.Terminals.T1
import services.crunch.CrunchTestLike
import services.crunch.deskrecs.{MockPortStateActor, RunnableDeskRecs}
import services.graphstages.{Buffer, CrunchMocks}

import scala.concurrent.duration._

class StreamingWorkloadSpec extends CrunchTestLike {
  implicit val timeout: Timeout = new Timeout(10 seconds)

  val mockCrunch: TryCrunch = CrunchMocks.mockCrunch
  val noDelay: Long = 0L
  val smallDelay: Long = 66L

  val portStateProbe = TestProbe("port-state")
  val flightsToDeskRecs: (FlightsWithSplits, MillisSinceEpoch) => DeskRecMinutes = (_: FlightsWithSplits, ms: MillisSinceEpoch) => {
    DeskRecMinutes(Seq(DeskRecMinute(T1, Queues.EeaDesk, ms, 0, 0, 0, 0)))
  }
  def newBuffer = Buffer(Iterable())
  val mockPortStateActor: ActorRef = system.actorOf(MockPortStateActor.props(portStateProbe, smallDelay))
  val (millisToCrunchSourceActor: ActorRef, _) = RunnableDeskRecs(mockPortStateActor, 30, airportConfig, flightsToDeskRecs, newBuffer).run()

  val askableSource: AskableActorRef = millisToCrunchSourceActor

  var days = List(List(0, 1, 2, 3, 4, 5, 6, 7), List(0))

  def nextDays: List[Int] = days match {
    case Nil => List()
    case head :: tail =>
      days = tail
      head
  }

  val midnight20190101 = SDate("2019-01-01T00:00")

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
