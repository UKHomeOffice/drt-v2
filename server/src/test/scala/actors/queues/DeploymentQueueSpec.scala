package actors.queues

import actors.persistent.SortedActorRefSource
import akka.actor.ActorRef
import akka.stream.javadsl.RunnableGraph
import akka.stream.scaladsl.GraphDSL.Implicits.SourceShapeArrow
import akka.stream.scaladsl.{GraphDSL, Sink}
import akka.stream.{ClosedShape, Materializer}
import akka.testkit.{ImplicitSender, TestProbe}
import drt.shared.CrunchApi.MillisSinceEpoch
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.actor.commands.{CrunchRequest, ProcessingRequest}
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import scala.collection.SortedSet


class DeploymentQueueSpec extends CrunchTestLike with ImplicitSender {
  val myNow: () => SDateLike = () => SDate("2020-05-06", europeLondonTimeZone)
  val durationMinutes = 60

  def startQueueActor(probe: TestProbe, crunchOffsetMinutes: Int): ActorRef = {
    val request = (millis: MillisSinceEpoch) => CrunchRequest(millis, crunchOffsetMinutes, durationMinutes)
    val source = new SortedActorRefSource(TestProbe().ref, request, SortedSet.empty[ProcessingRequest], "deployments")
    val graph = GraphDSL.createGraph(source) {
      implicit builder =>
        crunchRequests =>
          crunchRequests ~> Sink.actorRef(probe.ref, "complete")
          ClosedShape
    }
    RunnableGraph.fromGraph(graph).run(Materializer.createMaterializer(system))
  }

  val day: MillisSinceEpoch = myNow().millisSinceEpoch

  "Given a DeploymentQueueReadActor" >> {
    val zeroOffset = 0
    val twoHourOffset = 120

    "When I set a zero offset and send it an UpdatedMillis as 2020-05-06T00:00 BST" >> {
      "Then I should see a CrunchRequest for the same day (midnight BST is 23:00 the day before in UTC, but LocalDate should stay the same)" >> {
        val daysSourceProbe: TestProbe = TestProbe()
        val actor = startQueueActor(daysSourceProbe, zeroOffset)
        actor ! Set(day)
        daysSourceProbe.expectMsg(CrunchRequest(LocalDate(2020, 5, 6), zeroOffset, durationMinutes))
        success
      }
    }

    "When I set a 2 hour offset and send it an UpdatedMillis as 2020-05-06T00:00 BST" >> {
      "Then I should see a CrunchRequest for the day before as the offset pushes that day to cover the following midnight" >> {
        val daysSourceProbe: TestProbe = TestProbe()
        val actor = startQueueActor(daysSourceProbe, twoHourOffset)
        actor ! Set(day)
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
        actor ! Set(today, tomorrow)
        daysSourceProbe.expectMsg(CrunchRequest(LocalDate(2020, 5, 5), 120, durationMinutes))
        Thread.sleep(200)
        startQueueActor(daysSourceProbe, defaultAirportConfig.crunchOffsetMinutes)
        daysSourceProbe.expectMsg(CrunchRequest(LocalDate(2020, 5, 6), 120, durationMinutes))
        success
      }
    }
  }
}
