package services.crunch

import controllers.ArrivalGenerator
import drt.server.feeds.{ArrivalsFeedSuccess, DqManifests, ManifestsFeedSuccess}
import drt.shared._
import passengersplits.parsing.VoyageManifestParser.{ManifestDateOfArrival, ManifestTimeOfArrival, PassengerInfoJson, VoyageManifest}
import services.crunch.VoyageManifestGenerator._
import uk.gov.homeoffice.drt.arrivals.{Arrival, EventTypes, FeedArrival}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2}
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, PortCode, Queues}
import uk.gov.homeoffice.drt.time.SDate

import scala.collection.immutable.{List, Map, SortedMap}
import scala.concurrent.Await
import scala.concurrent.duration._

class ArrivalUpdatesCorrectlyAffectLoadsSpec extends CrunchTestLike {
  val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(TestConfig(
    now = () => SDate("2019-01-01T01:00"),
    setPcpTimes = TestDefaults.setPcpFromBest,
    airportConfig = defaultAirportConfig.copy(
      slaByQueue = Map(Queues.EGate -> 15, Queues.EeaDesk -> 25, Queues.NonEeaDesk -> 45),
      queuesByTerminal = SortedMap(T1 -> Seq(Queues.EeaDesk, Queues.NonEeaDesk, Queues.EGate), T2 -> Seq())
    )
  ))
  val arrivalOne = ArrivalGenerator.live(iata = "BA0001", terminal = T1, origin = PortCode("JFK"), schDt = "2019-01-01T00:00", totalPax = Option(100))

  val arrivalTwo = ArrivalGenerator.live(iata = "BA0002", terminal = T1, origin = PortCode("JFK"), schDt = "2019-01-01T00:05", totalPax = Option(117))

  "Given crunch inputs and an arrival" >> {

    "I should see the correct number of pax along with the right minutes affected" >> {

      "When I send in a new arrival with only a scheduled date set" >> {
        offerAndCheckResult(Seq(arrivalOne))
        success
      }
    }

    "When I send an updated estimated time" >> {
      val updatedArrival = arrivalOne.copy(
        estimated = Option(SDate("2019-01-01T00:07").millisSinceEpoch)
      )

      offerAndCheckResult(Seq(updatedArrival))
      success
    }

    "When I send an updated act pax" >> {
      val updatedArrival = arrivalOne.copy(
        estimated = Option(SDate("2019-01-01T00:07").millisSinceEpoch),
        totalPax = Option(105),
      )

      offerAndCheckResult(Seq(updatedArrival))
      success
    }

    "When I send an updated act pax & estimated time & manifest" >> {
      val updatedArrival = arrivalOne.copy(
        estimated = Option(SDate("2019-01-01T00:01").millisSinceEpoch),
        totalPax = Option(76),
      )
      val voyageManifests = ManifestsFeedSuccess(DqManifests(0, Set(
        manifestForArrival(updatedArrival.toArrival(LiveFeedSource), manifestPax(25, visa) ++
          manifestPax(26, euPassport) ++
          manifestPax(25, nonVisa))
      )))

      offerAndWait(crunch.manifestsLiveInput, voyageManifests)
      offerAndCheckResult(Seq(updatedArrival), Seq(Queues.NonEeaDesk))
      success
    }

    "When I send another updated act pax & estimated time & manifest" >> {
      val updatedArrival = arrivalOne.copy(
        estimated = Option(SDate("2019-01-01T00:25").millisSinceEpoch),
        totalPax = Option(35),
      )
      val voyageManifests = ManifestsFeedSuccess(DqManifests(0, Set(
        manifestForArrival(updatedArrival.toArrival(LiveFeedSource),
          manifestPax(7, visa) ++
            manifestPax(7, euPassport) ++
            manifestPax(7, euPassport) ++
            manifestPax(7, euPassport) ++
            manifestPax(7, nonVisa))
      )))

      offerAndWait(crunch.manifestsLiveInput, voyageManifests)
      offerAndCheckResult(Seq(updatedArrival), Seq(Queues.NonEeaDesk))
      success
    }

    "When I send a second arrival with a manifest" >> {
      val updatedArrival = arrivalOne.copy(
        estimated = Option(SDate("2019-01-01T00:25").millisSinceEpoch),
        totalPax = Option(211),
      )
      val voyageManifests = ManifestsFeedSuccess(DqManifests(0, Set(
        manifestForArrival(updatedArrival.toArrival(LiveFeedSource),
          manifestPax(42, visa) ++
            manifestPax(127, euPassport) ++
            manifestPax(42, nonVisa)),
        manifestForArrival(arrivalTwo.toArrival(LiveFeedSource), manifestPax(110, euIdCard) ++ manifestPax(7, nonVisa))
      )))

      offerAndWait(crunch.manifestsLiveInput, voyageManifests)
      offerAndCheckResult(Seq(updatedArrival, arrivalTwo), Seq(Queues.NonEeaDesk))
      success
    }

    "When I send a second arrival with a manifest" >> {
      val updatedArrivalOne = arrivalOne.copy(
        estimated = Option(SDate("2019-01-01T00:03").millisSinceEpoch),
        totalPax = Option(401),
      )
      val updatedArrivalTwo = arrivalTwo.copy(
        estimated = Option(SDate("2019-01-01T00:03").millisSinceEpoch),
        totalPax = Option(176),
      )
      val voyageManifests = ManifestsFeedSuccess(DqManifests(0, Set(
        manifestForArrival(updatedArrivalOne.toArrival(LiveFeedSource), manifestPax(300, visa) ++ manifestPax(99, euPassport) ++ manifestPax(2, nonVisa)),
        manifestForArrival(updatedArrivalTwo.toArrival(LiveFeedSource), manifestPax(30, euIdCard) ++ manifestPax(30, nonVisa) ++ manifestPax(116, euPassport))
      )))

      offerAndWait(crunch.manifestsLiveInput, voyageManifests)
      offerAndCheckResult(Seq(updatedArrivalOne, updatedArrivalTwo), Seq(Queues.NonEeaDesk))
      success
    }

    success
  }

  private def manifestForArrival(updatedArrival: Arrival, paxInfos: List[PassengerInfoJson]): VoyageManifest = {
    val schDateTime = SDate(updatedArrival.Scheduled)
    VoyageManifest(EventTypes.CI, PortCode("STN"), updatedArrival.Origin, updatedArrival.VoyageNumber, updatedArrival.CarrierCode, ManifestDateOfArrival(schDateTime.toISODateOnly), ManifestTimeOfArrival(schDateTime.toHoursAndMinutes), paxInfos)
  }

  private def offerAndCheckResult(arrivals: Seq[FeedArrival], queues: Seq[Queue] = Seq()): Unit = {
    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(arrivals))

    crunch.portStateTestProbe.fishForMessage(5.second) {
      case ps: PortState => paxLoadsAreCorrect(ps, arrivals, queues)
    }
  }

  private def paxLoadsAreCorrect(ps: PortState, arrivals: Seq[FeedArrival], queues: Seq[Queue]): Boolean = {
    val flights = ps.flights
    val crunchMins = ps.crunchMinutes

    val arrivalsExist = arrivals.foldLeft(true) { case (soFar, a) => soFar && flights.contains(a.unique) }

    val arrivalsPaxTotal = arrivals.flatMap(_.totalPax).sum

    val (firstPaxLoadMinute, paxLoadTotal, queuesOk) = crunchMins match {
      case cms if cms.nonEmpty =>
        val totalPax = cms.values.map(_.paxLoad).sum
        val nonZeroLoadMinutes = cms.values.filter(_.paxLoad > 0).toSeq
        val firstPaxMin = nonZeroLoadMinutes.map(_.minute).min
        val paxExistInCorrectQueues = queues.foldLeft(true) {
          case (okSoFar, q) => okSoFar && nonZeroLoadMinutes.map(_.queue).contains(q)
        }
        (firstPaxMin, Math.round(totalPax), paxExistInCorrectQueues)
      case _ => (0L, 0, false)
    }

    val earliestPcpMinuteAcrossArrivals = Await.result(TestDefaults
      .setPcpFromBest(arrivals.map(_.toArrival(LiveFeedSource)).toList)
      .map(_.minBy(_.PcpTime.getOrElse(Long.MaxValue))), 1.second).PcpTime.getOrElse(Long.MaxValue)
    val firstPaxMinuteEqualsPcpTime = firstPaxLoadMinute == earliestPcpMinuteAcrossArrivals
    val paxLoadEqualsActPax = paxLoadTotal == arrivalsPaxTotal

    arrivalsExist && firstPaxMinuteEqualsPcpTime && paxLoadEqualsActPax && queuesOk
  }
}
