package actors.migration

import akka.persistence.query.{EventEnvelope, NoOffset}
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import drt.shared.CrunchApi.MillisSinceEpoch
import server.protobuf.messages.CrunchState.{CrunchDiffMessage, FlightWithSplitsMessage}
import server.protobuf.messages.FlightsMessage.{FlightMessage, UniqueArrivalMessage}
import services.SDate
import services.crunch.CrunchTestLike


class CrunchStateMigrationSpec extends CrunchTestLike {



  /**
   * Considerations
   * 1) We need to retain the `createdAt` part so that the non-legacy actors use it rather than using now()
   *   - maybe send the protobuf messages to and actor that overrides the FlightsRouter & TerminalMinuteLike actors?
   *     they could use the same logic, ie grouping by terminal & day before passing them on to the relevant persisting
   *     actors
   *     2) We need to update the timestamp field of the snapshot table to match that of the original data
   *   - maybe the persisting actors can simply use the max createdAt field from the message that triggered the snapshot
   *     to update the timestamp using a raw slick query
   *     3) We can handle all three data type migrations from the same stream of persisted CrunchStateActor data
   *     4) We additionally have to handle the FlightsStateActor data once we've finished the CrunchStateActor data
   */
  "Given a stream of EventEnvelopes containing legacy CrunchDiffMessages with flights to remove and flight updates" >> {
    "When I ask for them to be re-persisted as non-legacy data" >> {
      "I should see each type of data sent as a protobuf message to the flights migration actor" >> {
        val createdAt = SDate("2020-10-01T00:00").millisSinceEpoch
        val scheduled = SDate("2020-10-02T12:10")
        val removal = UniqueArrivalMessage(Option(1), Option("T1"), Option(scheduled.millisSinceEpoch))
        val flight = FlightMessage()
        val fwsMsg = FlightWithSplitsMessage(Option(flight))
        val events = List(
          EventEnvelope(NoOffset, "some-legacy-persistence-id", 1, CrunchDiffMessage(Option(createdAt), None, Seq(removal), Seq(fwsMsg))),
        )
        val testProbe = TestProbe()

        val eventsSource = Source(events).map{
          case EventEnvelope(_, _, sequenceNr, CrunchDiffMessage(Some(createdAt), _, removals, updates, _, _, _, _)) =>
            FlightMessageMigration(sequenceNr, createdAt, removals, updates)

        }
        eventsSource.runWith(Sink.actorRef(testProbe.ref, "complete"))

        val expected = FlightMessageMigration(1, createdAt, Seq(removal), Seq(fwsMsg))

        testProbe.expectMsg(expected)
        testProbe.expectMsg("complete")

        success
      }
    }
  }
}

