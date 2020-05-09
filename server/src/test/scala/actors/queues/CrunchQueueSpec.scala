package actors.queues

import actors.{InMemoryStreamingJournal, SetDaysQueueSource}
import actors.queues.CrunchQueueReadActor.UpdatedMillis
import akka.actor.{ActorRef, Props, Terminated}
import akka.stream.OverflowStrategy
import akka.stream.Supervision.Stop
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import akka.testkit.TestProbe
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SDateLike
import services.SDate
import services.crunch.CrunchTestLike
import services.graphstages.Crunch

import scala.concurrent.duration._


class CrunchQueueSpec extends CrunchTestLike {

  def startReadActor(probe: TestProbe): ActorRef = {
    val source: SourceQueueWithComplete[MillisSinceEpoch] = Source
      .queue[MillisSinceEpoch](1, OverflowStrategy.backpressure)
      .throttle(1, 1 second)
      .toMat(Sink.foreach(probe.ref ! _))(Keep.left)
      .run()
    val actor = system.actorOf(Props(new CrunchQueueReadActor(InMemoryStreamingJournal, defaultAirportConfig.crunchOffsetMinutes)), "crunch-queue")
    actor ! SetDaysQueueSource(source)
    actor
  }

  val myNow: () => SDateLike = () => SDate("2020-05-06", Crunch.europeLondonTimeZone)
  val day: MillisSinceEpoch = myNow().millisSinceEpoch

  "Given a CrunchQueueReadActor" >> {
    "When I send it an UpdatedMillis message with one day in it" >> {
      "Then I should see the day as milliseconds in the source" >> {
        val daysSourceProbe: TestProbe = TestProbe()
        val actor = startReadActor(daysSourceProbe)
        actor ! UpdatedMillis(Iterable(day))
        daysSourceProbe.expectMsg(day)
        success
      }
    }

    "When I send it an UpdatedMillis message with 10 days in it and then ask it to shut down" >> {
      "Then when I start it again I should see a snapshot of the remaining days being processed, and the " >> {
        val daysSourceProbe: TestProbe = TestProbe()
        val actor = startReadActor(daysSourceProbe)
        val today = myNow().millisSinceEpoch
        val tomorrow = myNow().addDays(1).millisSinceEpoch
        watch(actor)
        actor ! UpdatedMillis(Iterable(today, tomorrow))
        daysSourceProbe.expectMsg(today)
        actor ! Stop
        expectMsgAllClassOf(classOf[Terminated])

        startReadActor(daysSourceProbe)
        daysSourceProbe.expectMsg(tomorrow)
        Thread.sleep(1000)
        success
      }
    }

  }
}
