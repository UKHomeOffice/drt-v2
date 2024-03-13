package controllers.application

import akka.actor.typed
import akka.actor.typed.ActorRef
import drt.server.feeds.Feed.FeedTick
import drt.server.feeds.FeedPoller.AdhocCheck
import drt.server.feeds.{ArrivalsFeedSuccess, Feed}
import drt.shared.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import module.DRTModule
import play.api.test.Helpers
import services.crunch.{CrunchSystem, CrunchTestLike}
import uk.gov.homeoffice.drt.arrivals.Passengers
import uk.gov.homeoffice.drt.crunchsystem.{DrtSystemInterface, ProdDrtSystem}
import uk.gov.homeoffice.drt.ports.{AirportConfig, LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.ports.config.Stn
import uk.gov.homeoffice.drt.service.{FeedService, ProdFeedService}
import uk.gov.homeoffice.drt.testsystem.MockDrtParameters
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt

class PortStateControllerSpec extends CrunchTestLike {
  "Given a PortStateController" >> {
    "When I import a live arrival and its live API manifest" >> {
      "Then I should eventually see the arrival with live splits in the crunch updates" >> {
        val scheduled = SDate("2021-01-01T00:00")
        val arrival = ArrivalGenerator.arrival("BA0001", sch = scheduled.millisSinceEpoch,
          feedSources = Set(LiveFeedSource), passengerSources = Map(LiveFeedSource -> Passengers(Option(100), None)))

        object LiveFeed {
          var queuedArrivals = List(arrival)
          def apply(): Feed[ActorRef[Feed.FeedTick]] = Feed(Feed.actorRefSource.mapAsync(1) { _ =>
            val arrivals = queuedArrivals
            queuedArrivals = List()
            Future.successful(ArrivalsFeedSuccess(Flights(arrivals), SDate.now()))
          }, 5.seconds, 5.seconds)
        }

        val drtInterface = newDrtInterface(LiveFeed())
        val controller = newController(drtInterface)

        val e: Future[CrunchSystem[ActorRef[FeedTick]]] = drtInterface.applicationService.run.map {
          case Some(cs) =>
            cs.feedService.liveFeedPollingActor ! AdhocCheck
            cs
        }

        Await.ready(e, 1.second)

        success
      }
    }
  }

  private def newController(interface: DrtSystemInterface) =
    new PortStateController(Helpers.stubControllerComponents(), interface)

  private def newDrtInterface(feed: Feed[ActorRef[Feed.FeedTick]]) =
    new ProdDrtSystem(Stn.config, MockDrtParameters(), () => SDate("2021-01-01T00:00")) {
      override val feedService: FeedService = new ProdFeedService(
        journalType, airportConfig, now, params, config, paxFeedSourceOrder, flightLookups, manifestLookups
      ) {
        override def liveArrivalsSource(port: PortCode): Feed[typed.ActorRef[FeedTick]] = feed
      }
    }
}
