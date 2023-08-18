package services.crunch

import controllers.ArrivalGenerator
import drt.server.feeds.ArrivalsFeedSuccess
import drt.shared.FlightsApi.Flights
import drt.shared._
import manifests.passengers.{BestAvailableManifest, ManifestPaxCount}
import manifests.{ManifestLookupLike, UniqueArrivalKey}
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.{Passengers, VoyageNumber}
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.{ApiFeedSource, LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._

class ArrivalsPaxMatchQueuePaxSpec extends CrunchTestLike {
  sequential
  isolated

  object MockHistoricManifestProvider extends ManifestLookupLike {
    var manifest: Option[BestAvailableManifest] = None
    var manifestPaxCount: Option[ManifestPaxCount] = None

    override def maybeBestAvailableManifest(arrivalPort: PortCode,
                                            departurePort: PortCode,
                                            voyageNumber: VoyageNumber,
                                            scheduled: SDateLike,
                                           ): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] = {
      val key = UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled)
      Future.successful((key, manifest))
    }

    override def historicManifestPax(arrivalPort: PortCode,
                                     departurePort: PortCode,
                                     voyageNumber: VoyageNumber,
                                     scheduled: SDateLike,
                                    ): Future[(UniqueArrivalKey, Option[ManifestPaxCount])] = {
      val key = UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled)
      Future.successful((key, manifestPaxCount))
    }
  }

  "Given a flight with terminal average splits" >> {
    "When I inspect the queue passengers" >> {
      "Then they should total the same as the passengers on the flight" >> {
        val scheduled = "2017-01-01T00:00Z"

        val arrivalPax = 112
        val arrival = ArrivalGenerator.arrival(schDt = scheduled, iata = "BA0001", terminal = T1, passengerSources = Map(LiveFeedSource -> Passengers(Option(arrivalPax), None)))

        val crunch = runCrunchGraph(TestConfig(
          now = () => SDate(scheduled),
//          historicManifestLookup = Option(MockHistoricManifestProvider),
        ))

        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrival))))

        crunch.portStateTestProbe.fishForMessage(5.seconds) {
          case ps: PortState =>
            val paxInQueues = paxLoadsFromPortState(ps, 10).map {
              case (_, queuesPax) => queuesPax.values.map(_.sum).sum
            }.sum
            paxInQueues == arrivalPax
        }

        MockHistoricManifestProvider.manifest = Option(BestAvailableManifest(VoyageManifestGenerator.manifestForArrival(arrival,
          List.fill(45)(PassengerInfoGenerator.passengerInfoJson(Nationality("GBR"), DocumentType("P"), Nationality("GBR"))) ++
            List.fill(17)(PassengerInfoGenerator.passengerInfoJson(Nationality("FRA"), DocumentType("P"), Nationality("FRA"))) ++
            List.fill(19)(PassengerInfoGenerator.passengerInfoJson(Nationality("USA"), DocumentType("P"), Nationality("USA"))) ++
            List.fill(7)(PassengerInfoGenerator.passengerInfoJson(Nationality("CHN"), DocumentType("P"), Nationality("CHN"))) ++
            List.fill(12)(PassengerInfoGenerator.passengerInfoJson(Nationality("GER"), DocumentType("P"), Nationality("GER"))) ++
            List.fill(13)(PassengerInfoGenerator.passengerInfoJson(Nationality("IND"), DocumentType("P"), Nationality("IND")))
        )))

        val arrivalv2 = arrival.copy(PassengerSources = arrival.PassengerSources + (LiveFeedSource -> Passengers(Option(100), None)))
        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Seq(arrivalv2))))

        //        offerAndWait(crunch.manifestsLiveInput, ManifestsFeedSuccess(DqManifests(1L, Seq(VoyageManifestGenerator.manifestForArrival(arrival,
//        List.fill(45)(PassengerInfoGenerator.passengerInfoJson(Nationality("GBR"), DocumentType("P"), Nationality("GBR"))) ++
//          List.fill(17)(PassengerInfoGenerator.passengerInfoJson(Nationality("FRA"), DocumentType("P"), Nationality("FRA"))) ++
//          List.fill(19)(PassengerInfoGenerator.passengerInfoJson(Nationality("USA"), DocumentType("P"), Nationality("USA"))) ++
//          List.fill(7)(PassengerInfoGenerator.passengerInfoJson(Nationality("CHN"), DocumentType("P"), Nationality("CHN"))) ++
//          List.fill(12)(PassengerInfoGenerator.passengerInfoJson(Nationality("GER"), DocumentType("P"), Nationality("GER"))) ++
//          List.fill(13)(PassengerInfoGenerator.passengerInfoJson(Nationality("IND"), DocumentType("P"), Nationality("IND")))
        //        )))))

        crunch.portStateTestProbe.fishForMessage(1.seconds) {
          case ps: PortState =>
            val passengers = ps.flights.values.headOption.map(_.apiFlight.PassengerSources)
            val feedSources = ps.flights.values.headOption.map(_.apiFlight.FeedSources)
            println(s"passengers: $passengers")
            println(s"feedSources: $feedSources")
            val hasApi = ps.flights.values.headOption.exists(_.apiFlight.PassengerSources.contains(ApiFeedSource))

            val paxInQueues = paxLoadsFromPortState(ps, 10).map {
              case (_, queuesPax) => queuesPax.values.map(_.sum).sum
            }.sum
            println(s"resultSummary: $paxInQueues, bestPax: ${ps.flights.values.headOption.map(_.apiFlight.bestPcpPaxEstimate(List(ApiFeedSource, LiveFeedSource)))}")
            hasApi && paxInQueues == 113
        }

        success
      }
    }
  }
}

