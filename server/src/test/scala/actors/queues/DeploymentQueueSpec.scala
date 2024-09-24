package actors.queues

import actors.persistent.SortedActorRefSource
import akka.actor.ActorRef
import akka.stream.javadsl.RunnableGraph
import akka.stream.scaladsl.GraphDSL.Implicits.SourceShapeArrow
import akka.stream.scaladsl.{GraphDSL, Sink}
import akka.stream.{ClosedShape, Materializer}
import akka.testkit.{ImplicitSender, TestProbe}
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import scala.collection.SortedSet


class DeploymentQueueSpec extends CrunchTestLike with ImplicitSender {
  val myNow: () => SDateLike = () => SDate("2020-05-06", europeLondonTimeZone)
  val durationMinutes = 60

  def startQueueActor(probe: TestProbe): ActorRef = {
    val source = new SortedActorRefSource(TestProbe().ref, SortedSet.empty[TerminalUpdateRequest], "deployments")
    val graph = GraphDSL.createGraph(source) {
      implicit builder =>
        crunchRequests =>
          crunchRequests ~> Sink.actorRef(probe.ref, "complete")
          ClosedShape
    }
    RunnableGraph.fromGraph(graph).run(Materializer.createMaterializer(system))
  }

  "Given a DeploymentQueueReadActor" >> {
    "When I set a zero offset and send it an UpdatedMillis as 2020-05-06T00:00 BST" >> {
      "Then I should see a CrunchRequest for the same day (midnight BST is 23:00 the day before in UTC, but LocalDate should stay the same)" >> {
        val daysSourceProbe: TestProbe = TestProbe()
        val actor = startQueueActor(daysSourceProbe)
        actor ! Set(TerminalUpdateRequest(T1, myNow().toLocalDate))
        daysSourceProbe.expectMsg(TerminalUpdateRequest(T1, LocalDate(2020, 5, 6)))
        success
      }
    }

    "When I send it an UpdatedMillis message with 2 days in it and then ask it to shut down and start it again" >> {
      "Then I should see the 1 remaining CrunchRequest being processed" >> {
        val daysSourceProbe: TestProbe = TestProbe()
        val actor = startQueueActor(daysSourceProbe)
        val today = myNow()
        val tomorrow = myNow().addDays(1)
        watch(actor)
        actor ! Set(today, tomorrow).map(d => TerminalUpdateRequest(T1, d.toLocalDate))
        daysSourceProbe.expectMsg(TerminalUpdateRequest(T1, LocalDate(2020, 5, 6)))
        Thread.sleep(200)
        startQueueActor(daysSourceProbe)
        daysSourceProbe.expectMsg(TerminalUpdateRequest(T1, LocalDate(2020, 5, 7)))
        success
      }
    }
  }
}
