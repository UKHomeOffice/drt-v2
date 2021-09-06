package actors.queues

import actors.SetCrunchRequestQueue
import actors.persistent.QueueLikeActor.UpdatedMillis
import actors.persistent.{QueueLikeActor, SortedActorRefSource}
import akka.Done
import akka.actor.{ActorRef, PoisonPill, Props, Terminated}
import akka.stream.javadsl.RunnableGraph
import akka.stream.scaladsl.GraphDSL.Implicits.SourceShapeArrow
import akka.stream.{ActorMaterializer, ClosedShape, KillSwitches}
import akka.stream.scaladsl.{GraphDSL, Sink, Source}
import akka.testkit.{ImplicitSender, TestProbe}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{PortStateQueueMinutes, SDateLike}
import drt.shared.dates.LocalDate
import services.SDate
import services.crunch.CrunchTestLike
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest
import services.graphstages.Crunch

import java.util.UUID
import scala.collection.mutable
import scala.concurrent.Future


class TestDeploymentQueueActor(now: () => SDateLike, offsetMinutes: Int, durationMinutes: Int)
  extends QueueLikeActor(now, offsetMinutes, durationMinutes) {
  override val maybeSnapshotInterval: Option[Int] = Option(1)
  override val queuedDays: mutable.SortedSet[CrunchRequest] = mutable.SortedSet()

  override def persistenceId: String = "test-deployment-queue"
}



class DeploymentQueueSpec extends CrunchTestLike with ImplicitSender {
  val myNow: () => SDateLike = () => SDate("2020-05-06", Crunch.europeLondonTimeZone)
  val durationMinutes = 60

  def startQueueActor(probe: TestProbe, crunchOffsetMinutes: Int): ActorRef = {
    val source = new SortedActorRefSource(TestProbe().ref, crunchOffsetMinutes, durationMinutes)
    val graph = GraphDSL.create(source) {
      implicit builder =>
        (crunchRequests) =>
          val ignore = Sink.ignore
          crunchRequests ~> ignore
          ClosedShape
    }
    val actorRefSource: ActorRef = RunnableGraph.fromGraph(graph).run(ActorMaterializer.create(system))
    val actor = system.actorOf(Props(new TestDeploymentQueueActor(myNow, crunchOffsetMinutes, durationMinutes)), "deployment-queue")
    actor ! SetCrunchRequestQueue(actorRefSource)
    actor
  }

  val day: MillisSinceEpoch = myNow().millisSinceEpoch

  "Given a DeploymentQueueReadActor" >> {
    val zeroOffset = 0
    val twoHourOffset = 120

    "When I set a zero offset and send it an UpdatedMillis as 2020-05-06T00:00 BST" >> {
      "Then I should see a CrunchRequest for the same day (midnight BST is 23:00 the day before in UTC, but LocalDate should stay the same)" >> {
        val daysSourceProbe: TestProbe = TestProbe()
        val actor = startQueueActor(daysSourceProbe, zeroOffset)
        actor ! UpdatedMillis(Iterable(day))
        daysSourceProbe.expectMsg(CrunchRequest(LocalDate(2020, 5, 6), zeroOffset, durationMinutes))
        success
      }
    }

    "When I set a 2 hour offset and send it an UpdatedMillis as 2020-05-06T00:00 BST" >> {
      "Then I should see a CrunchRequest for the day before as the offset pushes that day to cover the following midnight" >> {
        val daysSourceProbe: TestProbe = TestProbe()
        val actor = startQueueActor(daysSourceProbe, twoHourOffset)
        actor ! UpdatedMillis(Iterable(day))
        daysSourceProbe.expectMsg(CrunchRequest(LocalDate(2020, 5, 5), twoHourOffset, durationMinutes))
        success
      }
    }

    "When I send it an UpdatedMillis message with 2 days in it and then ask it to shut down and start it again" >> {
      "Then I should see the 1 remaining CrunchRequest being processed" >> {
        val daysSourceProbe: TestProbe = TestProbe()
        val actor = startQueueActor(daysSourceProbe, twoHourOffset)
        val today = myNow().millisSinceEpoch
        val tomorrow = myNow().addDays(1).millisSinceEpoch
        watch(actor)
        actor ! UpdatedMillis(Iterable(today, tomorrow))
        daysSourceProbe.expectMsg(CrunchRequest(LocalDate(2020, 5, 5), 120, durationMinutes))
        Thread.sleep(200)
        actor ! PoisonPill
        expectMsgAllClassOf(classOf[Terminated])

        startQueueActor(daysSourceProbe, defaultAirportConfig.crunchOffsetMinutes)
        daysSourceProbe.expectMsg(CrunchRequest(LocalDate(2020, 5, 6), 120, durationMinutes))
        success
      }
    }
  }
}
