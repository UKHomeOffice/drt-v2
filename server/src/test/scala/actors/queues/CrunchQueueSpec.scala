package actors.queues

import actors.acking.AckingReceiver.Ack
import actors.queues.QueueLikeActor.UpdatedMillis
import actors.{InMemoryStreamingJournal, SetDaysQueueSource}
import akka.actor.{ActorRef, PoisonPill, Props, Terminated}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import akka.testkit.{ImplicitSender, TestProbe}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SDateLike
import services.SDate
import services.crunch.CrunchTestLike
import services.graphstages.Crunch

import scala.concurrent.duration._


class TestCrunchQueueActor(now: () => SDateLike, offsetMinutes: Int)
  extends CrunchQueueActor(now, InMemoryStreamingJournal, offsetMinutes) {
  override val maybeSnapshotInterval: Option[Int] = Option(1)
}

class CrunchQueueSpec extends CrunchTestLike with ImplicitSender {
  val myNow: () => SDateLike = () => SDate("2020-05-06", Crunch.europeLondonTimeZone)

  def startQueueActor(probe: TestProbe): ActorRef = {
    val source: SourceQueueWithComplete[MillisSinceEpoch] = Source
      .queue[MillisSinceEpoch](1, OverflowStrategy.backpressure)
      .throttle(1, 1 second)
      .toMat(Sink.foreach(probe.ref ! _))(Keep.left)
      .run()
    val actor = system.actorOf(Props(new TestDeploymentQueueActor(myNow, defaultAirportConfig.crunchOffsetMinutes)), "crunch-queue")
    actor ! SetDaysQueueSource(source)
    actor
  }

  val day: MillisSinceEpoch = myNow().millisSinceEpoch

  "Given a CrunchQueueReadActor" >> {
    "When I send it an UpdatedMillis message with one day in it" >> {
      "Then I should see the day as milliseconds in the source" >> {
        val daysSourceProbe: TestProbe = TestProbe()
        val actor = startQueueActor(daysSourceProbe)
        actor ! UpdatedMillis(Iterable(day))
        daysSourceProbe.expectMsg(day)
        success
      }
    }

    "When I send it an UpdatedMillis message with 10 days in it and then ask it to shut down and start it again" >> {
      "Then I should see a snapshot of the remaining days being processed" >> {
        val daysSourceProbe: TestProbe = TestProbe()
        val actor = startQueueActor(daysSourceProbe)
        val today = myNow().millisSinceEpoch
        val tomorrow = myNow().addDays(1).millisSinceEpoch
        watch(actor)
        actor ! UpdatedMillis(Iterable(today, tomorrow))
        daysSourceProbe.expectMsg(today)
        Thread.sleep(200)
        actor ! PoisonPill
        expectMsgAllClassOf(classOf[Terminated])

        startQueueActor(daysSourceProbe)
        daysSourceProbe.expectMsg(tomorrow)
        success
      }
    }
  }
}
