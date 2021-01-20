package services.crunch.deskrecs

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

  "Given a stream of flights and a stream of manifests" >> {
    "When I run both streams" >> {
      "I should be able to get a future containing both the flights and the manifests" >> {
        val flight = ApiFlightWithSplits(ArrivalGenerator.arrival("BA0001"), Set())
        val flights = FlightsWithSplits(Seq(flight))
        val flightsProvider = (_: MillisSinceEpoch) => Future(Source(List(flights)))

        val manifest = VoyageManifestGenerator.voyageManifest()
        val manifests = VoyageManifests(Set(manifest))
        val manifestsProvider = (_: MillisSinceEpoch) => Future(Source(List(manifests)))

        val dayMillis = SDate("2021-06-01T12:00").millisSinceEpoch

        val eventualResult: Future[immutable.Seq[(MillisSinceEpoch, FlightsWithSplits, VoyageManifests)]] = Source(List(dayMillis))
          .mapAsync(1)(day => flightsProvider(day).map(flightsStream => (day, flightsStream)))
          .mapAsync(1) { case (day, flightsSource) =>
            manifestsProvider(day).map(manifestsStream => (day, flightsSource, manifestsStream))
          }
          .flatMapConcat { case (day, flights, manifests) =>
            flights.fold(FlightsWithSplits.empty)(_ ++ _).map(fws => (day, fws, manifests))
          }
          .flatMapConcat { case (day, fws, manifestsSource) =>
            manifestsSource.fold(VoyageManifests.empty)(_ ++ _).map(vms => (day, fws, vms))
          }
          .runWith(Sink.seq)

        val result: immutable.Seq[(MillisSinceEpoch, FlightsWithSplits, VoyageManifests)] = Await.result(eventualResult, 1 second)
        val expected = Seq((dayMillis, flights, manifests))

        result === expected
      }
    }
  }

}
