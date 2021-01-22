package services.crunch.deskrecs

import akka.NotUsed
import akka.actor.Actor
import akka.stream.scaladsl.{Sink, Source}
import controllers.ArrivalGenerator
import drt.shared.ApiFlightWithSplits
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.SDate
import services.crunch.{CrunchTestLike, VoyageManifestGenerator}

import scala.collection.immutable
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class MockActor(somethingToReturn: List[Any]) extends Actor {
  override def receive: Receive = {
    case _ => sender() ! Source(somethingToReturn)
  }
}

case class CrunchStuff(startTime: MillisSinceEpoch, flights: FlightsWithSplits, manifests: VoyageManifests)

class RunnableDynamicDeskRecsSpec extends CrunchTestLike {

  "Given a stream of days and a flights provider and a manifests provider" >> {

    val flights: FlightsWithSplits = FlightsWithSplits(Seq(ApiFlightWithSplits(ArrivalGenerator.arrival("BA0001"), Set())))
    val flightsProvider: MillisSinceEpoch => Future[Source[FlightsWithSplits, NotUsed]] = (_: MillisSinceEpoch) => Future(Source(List(flights)))
    val manifests: VoyageManifests = VoyageManifests(Set(VoyageManifestGenerator.voyageManifest()))
    val manifestsProvider: MillisSinceEpoch => Future[Source[VoyageManifests, NotUsed]] = (_: MillisSinceEpoch) => Future(Source(List(manifests)))
    val dayMillis = SDate("2021-06-01T12:00").millisSinceEpoch

    "When I add flights" >> {
      "I should get a stream with the day's flights added to the day" >> {
        val withFlights = addFlights(Source(List(dayMillis)), flightsProvider)
        val eventualResult = withFlights.runWith(Sink.seq)

        val result: immutable.Seq[(MillisSinceEpoch, FlightsWithSplits)] = Await.result(eventualResult, 1 second)
        val expected = Seq((dayMillis, flights))

        result === expected
      }
    }

    "When I add flights and then manifests" >> {
      "I should get a stream with the day's flights and manifests added to the day" >> {
        val withFlights = addFlights(Source(List(dayMillis)), flightsProvider)
        val withManifests = addManifests(withFlights, manifestsProvider)
        val eventualResult = withManifests.runWith(Sink.seq)

        val result: immutable.Seq[(MillisSinceEpoch, FlightsWithSplits, VoyageManifests)] = Await.result(eventualResult, 1 second)
        val expected = Seq((dayMillis, flights, manifests))

        result === expected
      }
    }
  }

  private def addManifests(dayWithFlights: Source[(MillisSinceEpoch, FlightsWithSplits), NotUsed],
                           manifestsProvider: MillisSinceEpoch => Future[Source[VoyageManifests, NotUsed]]): Source[(MillisSinceEpoch, FlightsWithSplits, VoyageManifests), NotUsed] = {
    dayWithFlights
      .mapAsync(1) { case (day, flightsSource) =>
        manifestsProvider(day).map(manifestsStream => (day, flightsSource, manifestsStream))
      }
      .flatMapConcat { case (day, fws, manifestsSource) =>
        manifestsSource.fold(VoyageManifests.empty)(_ ++ _).map(vms => (day, fws, vms))
      }
  }

  private def addFlights(days: Source[MillisSinceEpoch, NotUsed],
                         flightsProvider: MillisSinceEpoch => Future[Source[FlightsWithSplits, NotUsed]]): Source[(MillisSinceEpoch, FlightsWithSplits), NotUsed] = {
    days
      .mapAsync(1) { day =>
        flightsProvider(day).map(flightsStream => (day, flightsStream))
      }
      .flatMapConcat { case (day, flights) =>
        flights.fold(FlightsWithSplits.empty)(_ ++ _).map(fws => (day, fws))
      }
  }
}
