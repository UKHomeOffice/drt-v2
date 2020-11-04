package actors.minutes

import actors.ManifestLookupsLike
import actors.PartitionedPortStateActor.{GetStateForDateRange, PointInTimeQuery}
import actors.daily.RequestAndTerminate
import actors.minutes.MinutesActorLike.{ManifestLookup, ManifestsUpdate}
import actors.queues.ManifestRouterActor
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser._
import services.SDate
import services.crunch.CrunchTestLike

import scala.collection.immutable
import scala.collection.immutable.List
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

class ManifestsRouterActorSpec extends CrunchTestLike {

  val date: SDateLike = SDate("2020-01-01T00:00")

  implicit val mat: ActorMaterializer = ActorMaterializer.create(system)

  case class MockManifestLookupWithTestProbe(system: ActorSystem, testActor: ActorRef) extends ManifestLookupsLike {

    override implicit val ec: ExecutionContext = system.dispatcher
    override val now: () => SDateLike = () => SDate.now()
    override val requestAndTerminateActor: ActorRef = testActor

    override val manifestsByDayLookup: ManifestLookup = (date: UtcDate, maybePit: Option[MillisSinceEpoch]) => {
      requestAndTerminateActor ! (date, maybePit)
      Future(VoyageManifests.empty)
    }
  }

  val noopUpdates: ManifestsUpdate = (_: UtcDate, _: VoyageManifests) => Future(Unit)


  "Given a manifest for a date " +
    "When it is saved" >> {

    val testProbe = TestProbe()
    val manifestRouterActor: ActorRef = manifestRouterActorWithTestProbe(testProbe)

    val manifest = manifestForDate("2019-11-20")

    val manifests = VoyageManifests(Set(manifest))


    "Then it should be sent to the actor for the correct day" >> {

      manifestRouterActor ! manifests

      testProbe.fishForMessage(1 second) {
        case RequestAndTerminate(_, m) =>
          m === manifests
      }

      success
    }

    "Given I request manifests for a date range" >> {
      "Then each of the dates requested should be queried" >> {
        val testProbe = TestProbe()
        val manifestRouterActor = manifestRouterActorWithTestProbe(testProbe)

        val result = Await.result(manifestRouterActor.ask(
          GetStateForDateRange(SDate("2020-11-01T00:00Z").millisSinceEpoch, SDate("2020-11-02T23:59Z").millisSinceEpoch)
        ).mapTo[Source[VoyageManifests, NotUsed]], 1 second)

        result.runWith(Sink.seq)

        testProbe.expectMsgAllOf((UtcDate(2020, 11, 1), None), (UtcDate(2020, 11, 2), None))

        success
      }
    }

    "Given I request manifests for a date range at a point in time" >> {
      "Then each of the dates requested should be queried with the correct point in time" >> {
        val testProbe = TestProbe()
        val manifestRouterActor = manifestRouterActorWithTestProbe(testProbe)

        val pit = SDate("2020-11-05T00:00Z").millisSinceEpoch
        val result = Await.result(manifestRouterActor.ask(
          PointInTimeQuery(
            pit,
            GetStateForDateRange(SDate("2020-11-01T00:00Z").millisSinceEpoch, SDate("2020-11-02T23:59Z").millisSinceEpoch)
          )

        ).mapTo[Source[VoyageManifests, NotUsed]], 1 second)

        result.runWith(Sink.seq)

        testProbe.expectMsgAllOf((UtcDate(2020, 11, 1), Option(pit)), (UtcDate(2020, 11, 2), Option(pit)))

        success
      }
    }

    "Given I request manifests for a date range at a point in time" >> {
      "Then manifests for all those dates should be returned in the stream" >> {

        val manifest1 = manifestForDate("2020-11-01")
        val manifest2 = manifestForDate("2020-11-02")
        val manifest3 = manifestForDate("2020-11-03")
        val manifestsLookup = MockManifestsLookup()
        val testManifests = VoyageManifests(Set(manifest1, manifest2, manifest3))
        val manifestRouterActor = system.actorOf(
          ManifestRouterActor.props(manifestsLookup.lookup(testManifests), noopUpdates)
        )

        val resultSource: Future[Source[VoyageManifests, NotUsed]] = manifestRouterActor.ask(
          GetStateForDateRange(
            SDate("2020-11-01T00:00Z").millisSinceEpoch,
            SDate("2020-11-02T23:59Z").millisSinceEpoch
          )
        ).mapTo[Source[VoyageManifests, NotUsed]]

        val result = Await.result(ManifestRouterActor.runAndCombine(resultSource), 1 second)

        val expected = VoyageManifests(Set(manifest1, manifest2))

        result === expected
      }
    }
  }

  def manifestForDate(date: String) = {
    VoyageManifest(EventTypes.DC,
      defaultAirportConfig.portCode,
      PortCode("JFK"),
      VoyageNumber("0001"),
      CarrierCode("BA"),
      ManifestDateOfArrival(date),
      ManifestTimeOfArrival("00:00"),
      List(
        PassengerInfoJson(Option(DocumentType("P")),
          Nationality("GBR"),
          EeaFlag("EEA"), Option(PaxAge(11)), Option(PortCode("LHR")), InTransit("N"), Option(Nationality("GBR")), Option(Nationality("GBR")), None),
        PassengerInfoJson(Option(DocumentType("P")), Nationality("GBR"), EeaFlag("EEA"), Option(PaxAge(11)), Option(PortCode("LHR")), InTransit("N"), Option(Nationality("GBR")), Option(Nationality("GBR")), None)
      ))
  }

  def manifestRouterActorWithTestProbe(probe: TestProbe): ActorRef = {
    val mockLookup = MockManifestLookupWithTestProbe(system, probe.ref)

    system.actorOf(
      ManifestRouterActor.props(mockLookup.manifestsByDayLookup, mockLookup.updateManifests)
    )
  }
}
