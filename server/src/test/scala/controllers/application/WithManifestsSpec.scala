package controllers.application

import drt.shared.{ArrivalGenerator, ArrivalKey}
import org.specs2.mutable.SpecificationLike
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.crunch.{CrunchTestLike, VoyageManifestGenerator}
import uk.gov.homeoffice.drt.arrivals.{Arrival, Passengers}
import uk.gov.homeoffice.drt.ports.{ApiFeedSource, PortCode}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike, UtcDate}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class WithManifestsSpec extends CrunchTestLike with SpecificationLike {
  def mockProvider(manifests: Map[UtcDate, VoyageManifests]): UtcDate => Future[VoyageManifests] =
    date =>
      manifests.get(date)
        .map(Future.successful)
        .getOrElse(Future.successful(VoyageManifests.empty))

  val scheduledDay1: SDateLike = SDate(UtcDate(2023, 4, 26))
  val scheduled1: SDateLike = scheduledDay1.addHours(6)
  val arrival1: Arrival = ArrivalGenerator.arrival(sch = scheduled1.millisSinceEpoch, origin = PortCode("JFK"), passengerSources = Map(ApiFeedSource -> Passengers(Option(100), None)))

  val scheduledDay2: SDateLike = SDate(UtcDate(2023, 4, 27))
  val scheduled2: SDateLike = scheduledDay2.addHours(12)
  val arrival2: Arrival = ArrivalGenerator.arrival(sch = scheduled2.millisSinceEpoch, origin = PortCode("BHX"), passengerSources = Map(ApiFeedSource -> Passengers(Option(100), None)))

  val manifests: Map[UtcDate, VoyageManifests] = Map(
    scheduledDay1.toUtcDate -> VoyageManifests(Set(VoyageManifestGenerator.manifestForArrival(arrival1, List()))),
    scheduledDay2.toUtcDate -> VoyageManifests(Set(VoyageManifestGenerator.manifestForArrival(arrival2, List()))),
  )

  "Given an arrival key" >> {
    "When a manifest exists for that arrival" >> {
      "I should get a manifest summary" >> {
        val result = Await.result(ManifestsController.manifestsForFlights(mockProvider(manifests))(List(ArrivalKey(arrival1))), 1.second)

        result === manifests(scheduledDay1.toUtcDate)
      }
    }
  }

  "Given arrival keys for 2 different scheduled days" >> {
    "When manifests exist for them" >> {
      "I should get the manifest summaries" >> {
        val result = Await.result(ManifestsController.manifestsForFlights(mockProvider(manifests))(List(ArrivalKey(arrival1), ArrivalKey(arrival2))), 1.second)

        result === VoyageManifests(manifests(scheduledDay1.toUtcDate).manifests ++ manifests(scheduledDay2.toUtcDate).manifests)
      }
    }
  }

  "Given one arrival key" >> {
    "When multiple manifests exist on the scheduled date" >> {
      "I should only get the manifest summary for the one arrival" >> {
        val multiManifests = Map(
          scheduledDay1.toUtcDate -> VoyageManifests(Set(
            VoyageManifestGenerator.voyageManifest(),
            VoyageManifestGenerator.manifestForArrival(arrival1, List()),
            VoyageManifestGenerator.voyageManifest(),
        )))
        val result = Await.result(ManifestsController.manifestsForFlights(mockProvider(multiManifests))(List(ArrivalKey(arrival1))), 1.second)

        result === VoyageManifests(Set(VoyageManifestGenerator.manifestForArrival(arrival1, List())))
      }
    }
  }
}
