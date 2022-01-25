package actors.routing.minutes

import actors.ManifestLookupsLike
import actors.PartitionedPortStateActor.{GetStateForDateRange, PointInTimeQuery}
import actors.persistent.QueueLikeActor.UpdatedMillis
import actors.persistent.staffing.{GetFeedStatuses, GetState}
import actors.persistent.{ApiFeedState, ManifestRouterActor}
import actors.routing.minutes.MinutesActorLike.{ManifestLookup, ManifestsUpdate}
import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import uk.gov.homeoffice.drt.time.UtcDate
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser._
import server.feeds.{DqManifests, ManifestsFeedFailure, ManifestsFeedSuccess}
import services.SDate
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.ports.{ApiFeedSource, PaxAge, PortCode}

import scala.collection.immutable.List
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

class ManifestsRouterActorSpec extends CrunchTestLike {

  val date: SDateLike = SDate("2020-01-01T00:00")

  case class MockManifestLookupWithTestProbe(system: ActorSystem, testActor: ActorRef) extends ManifestLookupsLike {

    override implicit val ec: ExecutionContext = system.dispatcher

    override val requestAndTerminateActor: ActorRef = testActor

    override val manifestsByDayLookup: ManifestLookup = (date: UtcDate, maybePit: Option[MillisSinceEpoch]) => {
      requestAndTerminateActor ! ((date, maybePit))
      Future(VoyageManifests.empty)
    }
  }

  val noopUpdates: ManifestsUpdate = (_: UtcDate, _: VoyageManifests) => Future(UpdatedMillis.empty)

  "When sending an ApiFeedResponse" >> {
    "Given a Success response with 1 manifest" >> {
      val mockLookup = MockManifestsLookup()
      val manifestRouterActor: ActorRef = manifestRouterActorWithMock(mockLookup)
      val creationDate = SDate("2020-11-20T12:00Z")

      val manifest = manifestForDate("2020-11-20")

      val manifestFeedSuccess = ManifestsFeedSuccess(DqManifests("lastSeen.zip", Set(manifest)), creationDate)

      "Then it should be sent to the actor for the correct day" >> {

        Await.result(manifestRouterActor.ask(manifestFeedSuccess), 1 second)
        val expected = List((UtcDate(2020, 11, 20), VoyageManifests(Set(manifest))))
        mockLookup.paramsUpdate === expected
      }
    }

    "Given a Success response with 1 manifest" >> {
      val mockLookup = MockManifestsLookup()
      val manifestRouterActor: ActorRef = manifestRouterActorWithMock(mockLookup)
      val creationDate = SDate("2020-11-20T12:00Z")

      val manifest = manifestForDate("2020-11-20")

      val manifestFeedSuccess = ManifestsFeedSuccess(DqManifests("lastSeen.zip", Set(manifest)), creationDate)

      "Then it should update the last seen zip file name" >> {
        Await.result(manifestRouterActor.ask(manifestFeedSuccess), 1 second)

        val result: ApiFeedState = Await.result(manifestRouterActor.ask(GetState).mapTo[ApiFeedState], 1 second)

        result.latestZipFilename === "lastSeen.zip"
      }

      "Then it should record the successful response" >> {
        Await.result(manifestRouterActor.ask(manifestFeedSuccess), 1 second)

        val result: ApiFeedState = Await.result(manifestRouterActor.ask(GetState).mapTo[ApiFeedState], 1 second)

        result.maybeSourceStatuses === Option(
          FeedSourceStatuses(
            ApiFeedSource,
            FeedStatuses(
              List(FeedStatusSuccess(creationDate.millisSinceEpoch, 1)),
              Option(creationDate.millisSinceEpoch),
              None,
              Option(creationDate.millisSinceEpoch)
            )
          )
        )
      }
    }

    "Given a Failure response" >> {
      val mockLookup = MockManifestsLookup()
      val manifestRouterActor: ActorRef = manifestRouterActorWithMock(mockLookup)

      val creationDate = SDate("2020-11-20T12:00Z")

      val manifestFeedFailure = ManifestsFeedFailure("Failed", creationDate)

      "Then it should record the failure response" >> {
        Await.result(manifestRouterActor.ask(manifestFeedFailure), 1 second)

        val result: ApiFeedState = Await.result(manifestRouterActor.ask(GetState).mapTo[ApiFeedState], 2 seconds)

        result.maybeSourceStatuses === Option(
          FeedSourceStatuses(
            ApiFeedSource,
            FeedStatuses(
              statuses = List(FeedStatusFailure(creationDate.millisSinceEpoch, "Failed")),
              lastSuccessAt = None,
              lastFailureAt = Option(creationDate.millisSinceEpoch),
              lastUpdatesAt = None
            )
          )
        )
      }
      "Then it should respond with the feed statuses when asked" >> {
        Await.result(manifestRouterActor.ask(manifestFeedFailure), 1 second)

        val result: Option[FeedSourceStatuses] = Await.result(manifestRouterActor.ask(GetFeedStatuses).mapTo[Option[FeedSourceStatuses]], 1 second)

        result === Option(
          FeedSourceStatuses(
            ApiFeedSource,
            FeedStatuses(
              statuses = List(FeedStatusFailure(creationDate.millisSinceEpoch, "Failed")),
              lastSuccessAt = None,
              lastFailureAt = Option(creationDate.millisSinceEpoch),
              lastUpdatesAt = None
            )
          )
        )
      }
    }
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

  val mockSubscriber: ActorRef = TestProbe().ref

  "Given I request manifests for a date range at a point in time" >> {
    "Then manifests for all those dates should be returned in the stream" >> {

      val manifest1 = manifestForDate("2020-11-01")
      val manifest2 = manifestForDate("2020-11-02")
      val manifest3 = manifestForDate("2020-11-03")
      val manifestsLookup = MockManifestsLookup()
      val testManifests = VoyageManifests(Set(manifest1, manifest2, manifest3))
      val manifestRouterActor = system.actorOf(
        Props(new ManifestRouterActor(manifestsLookup.lookup(testManifests), noopUpdates))
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


  def manifestForDate(date: String): VoyageManifest = {
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
      Props(new ManifestRouterActor(mockLookup.manifestsByDayLookup, mockLookup.updateManifests))
    )
  }

  def manifestRouterActorWithMock(mock: MockManifestsLookup): ActorRef =
    system.actorOf(
      Props(new ManifestRouterActor(mock.lookup(), mock.update))
    )
}
