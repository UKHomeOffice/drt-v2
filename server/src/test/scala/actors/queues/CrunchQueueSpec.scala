package actors.queues

import actors.persistent.SortedActorRefSource
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.stream.javadsl.RunnableGraph
import org.apache.pekko.stream.scaladsl.GraphDSL.Implicits.SourceShapeArrow
import org.apache.pekko.stream.scaladsl.{GraphDSL, Sink}
import org.apache.pekko.stream.{ClosedShape, Materializer}
import org.apache.pekko.testkit.{ImplicitSender, TestProbe}
import drt.shared.CrunchApi.MillisSinceEpoch
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import scala.collection.SortedSet


class CrunchQueueSpec extends CrunchTestLike with ImplicitSender {
  val myNow: () => SDateLike = () => SDate("2020-05-06", europeLondonTimeZone)

  val durationMinutes = 60
  def startQueueActor(probe: TestProbe, initialQueue: SortedSet[TerminalUpdateRequest]): ActorRef = {
    val source = new SortedActorRefSource(TestProbe().ref, initialQueue, "desk-recs")
    val graph = GraphDSL.createGraph(source) {
      implicit builder =>
        crunchRequests =>
          crunchRequests ~> Sink.actorRef(probe.ref, "complete")
          ClosedShape
    }
    RunnableGraph.fromGraph(graph).run(Materializer.createMaterializer(system))
  }

  val day: MillisSinceEpoch = myNow().millisSinceEpoch

  "Given a CrunchQueueReadActor" >> {
    "When I set a zero offset and an initial queue with crunch request for 2022-06-01" >> {
      "Then I should see a CrunchRequest for that day" >> {
        val daysSourceProbe: TestProbe = TestProbe()
        val initialQueue = SortedSet(TerminalUpdateRequest(T1, LocalDate(2022, 6, 1)))
        startQueueActor(daysSourceProbe, initialQueue)
        daysSourceProbe.expectMsg(TerminalUpdateRequest(T1, LocalDate(2022, 6, 1)))
        success
      }
    }

    "When I set a zero offset and send it an UpdatedMillis as 2020-05-06T00:00 BST" >> {
      "Then I should see a CrunchRequest for the day before in UTC (midnight BST is 23:00 the day before in UTC)" >> {
        val daysSourceProbe: TestProbe = TestProbe()
        val actor = startQueueActor(daysSourceProbe, SortedSet())
        actor ! TerminalUpdateRequest(T1, LocalDate(2020, 5, 6))
        daysSourceProbe.expectMsg(TerminalUpdateRequest(T1, LocalDate(2020, 5, 6)))
        success
      }
    }

    "When I set a 2 hour offset and send it an UpdatedMillis as 2020-05-06T00:00 BST" >> {
      "Then I should see a CrunchRequest for the day before as the offset pushes that day to cover the following midnight" >> {
        val daysSourceProbe: TestProbe = TestProbe()
        val actor = startQueueActor(daysSourceProbe, SortedSet())
        actor ! TerminalUpdateRequest(T1, LocalDate(2020, 5, 5))
        daysSourceProbe.expectMsg(TerminalUpdateRequest(T1, LocalDate(2020, 5, 5)))
        success
      }
    }

    "When I send it an UpdatedMillis message with 2 days in it, with an offset of 120 minutes and then ask it to shut down and start it again" >> {
      "Then I should see the 1 remaining CrunchRequest being processed" >> {
        val daysSourceProbe: TestProbe = TestProbe()
        val actor = startQueueActor(daysSourceProbe, SortedSet())
        watch(actor)
        actor ! Set(TerminalUpdateRequest(T1, LocalDate(2020, 5, 5)), TerminalUpdateRequest(T1, LocalDate(2020, 5, 6)))
        daysSourceProbe.expectMsg(TerminalUpdateRequest(T1, LocalDate(2020, 5, 5)))
        Thread.sleep(200)
        startQueueActor(daysSourceProbe, SortedSet())
        daysSourceProbe.expectMsg(TerminalUpdateRequest(T1, LocalDate(2020, 5, 6)))
        success
      }
    }
  }
}
