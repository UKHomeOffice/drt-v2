package controllers.application

import drt.shared.{ArrivalGenerator, ArrivalKey}
import org.specs2.mutable.SpecificationLike
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.crunch.{CrunchTestLike, VoyageManifestGenerator}
import uk.gov.homeoffice.drt.arrivals.Passengers
import uk.gov.homeoffice.drt.ports.{ApiFeedSource, PortCode}
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class WithManifestsSpec extends CrunchTestLike with SpecificationLike {
  def mockProvider(manifests: Map[Long, VoyageManifests]): (Long, Long) => Future[VoyageManifests] =
    (start, _) => manifests.get(start)
      .map(Future.successful)
      .getOrElse(Future.successful(VoyageManifests.empty))

  val scheduledDay1 = SDate(UtcDate(2023, 4, 26))
  val scheduled1 = scheduledDay1.addHours(6)
  val arrival1 = ArrivalGenerator.arrival(sch = scheduled1.millisSinceEpoch, origin = PortCode("JFK"), passengerSources = Map(ApiFeedSource -> Passengers(Option(100), None)))

  val scheduledDay2 = SDate(UtcDate(2023, 4, 27))
  val scheduled2 = scheduledDay2.addHours(12)
  val arrival2 = ArrivalGenerator.arrival(sch = scheduled2.millisSinceEpoch, origin = PortCode("BHX"), passengerSources = Map(ApiFeedSource -> Passengers(Option(100), None)))

  val manifests = Map(
    scheduledDay1.millisSinceEpoch -> VoyageManifests(Set(VoyageManifestGenerator.manifestForArrival(arrival1, List()))),
    scheduledDay2.millisSinceEpoch -> VoyageManifests(Set(VoyageManifestGenerator.manifestForArrival(arrival2, List()))),
  )

  "Given an arrival key" >> {
    "When a manifest exists for that arrival" >> {
      "I should get a manifest summary" >> {
        val result = Await.result(WithManifests.manifestsForFlights(mockProvider(manifests))(List(ArrivalKey(arrival1))), 1.second)

        result === manifests(scheduledDay1.millisSinceEpoch)
      }
    }
  }

  "Given arrival keys for 2 different scheduled days" >> {
    "When manifests exist for them" >> {
      "I should get the manifest summaries" >> {
        val result = Await.result(WithManifests.manifestsForFlights(mockProvider(manifests))(List(ArrivalKey(arrival1), ArrivalKey(arrival2))), 1.second)

        result === VoyageManifests(manifests(scheduledDay1.millisSinceEpoch).manifests ++ manifests(scheduledDay2.millisSinceEpoch).manifests)
      }
    }
  }

  "Given one arrival key" >> {
    "When multiple manifests exist on the scheduled date" >> {
      "I should only get the manifest summary for the one arrival" >> {
        val multiManifests = Map(
          scheduledDay1.millisSinceEpoch -> VoyageManifests(Set(
            VoyageManifestGenerator.voyageManifest(),
            VoyageManifestGenerator.manifestForArrival(arrival1, List()),
            VoyageManifestGenerator.voyageManifest(),
        )))
        val result = Await.result(WithManifests.manifestsForFlights(mockProvider(multiManifests))(List(ArrivalKey(arrival1))), 1.second)

        result === VoyageManifests(Set(VoyageManifestGenerator.manifestForArrival(arrival1, List())))
      }
    }
  }
}
