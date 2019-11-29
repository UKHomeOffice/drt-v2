package services.crunch

import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared.Queues.Queue
import drt.shared.Terminals.{T1, T2}
import drt.shared._
import passengersplits.parsing.VoyageManifestParser.{ManifestDateOfArrival, ManifestTimeOfArrival, PassengerInfoJson, VoyageManifest}
import server.feeds.{ArrivalsFeedSuccess, DqManifests, ManifestsFeedSuccess}
import services.SDate
import services.crunch.VoyageManifestGenerator._

import scala.collection.immutable.List
import scala.concurrent.duration._

class
ArrivalUpdatesCorrectlyAffectLoads extends CrunchTestLike {
  val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(
    now = () => SDate("2019-01-01T01:00"),
    pcpArrivalTime = pcpForFlightFromBest,
    airportConfig = airportConfig.copy(
      queues = Map(T1 -> Seq(Queues.EeaDesk, Queues.NonEeaDesk, Queues.EGate), T2 -> Seq())
    )
  )
  val arrivalOne: Arrival = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, origin = PortCode("JFK"), schDt = "2019-01-01T00:00", actPax = Option(100))

  val arrivalTwo: Arrival = ArrivalGenerator.arrival(iata = "BA0002", terminal = T1, origin = PortCode("JFK"), schDt = "2019-01-01T00:05", actPax = Option(117))

  "Given crunch inputs and an arrival" >> {

    "I should see the correct number of pax along with the right minutes affected" >> {

      "When I send in a new arrival with only a scheduled date set" >> {
        offerAndCheckResult(Seq(arrivalOne))
        success
      }
    }

    "When I send an updated estimated time" >> {
      val updatedArrival = arrivalOne.copy(
        Estimated = Option(SDate("2019-01-01T00:07").millisSinceEpoch)
      )

      offerAndCheckResult(Seq(updatedArrival))
      success
    }

    "When I send an updated act pax" >> {
      val updatedArrival = arrivalOne.copy(
        Estimated = Option(SDate("2019-01-01T00:07").millisSinceEpoch),
        ActPax = Option(105)
      )

      offerAndCheckResult(Seq(updatedArrival))
      success
    }

    "When I send an updated act pax & estimated time & manifest" >> {
      val updatedArrival = arrivalOne.copy(
        Estimated = Option(SDate("2019-01-01T00:01").millisSinceEpoch),
        ActPax = Option(76)
      )
      val voyageManifests = ManifestsFeedSuccess(DqManifests("", Set(
        manifestForArrival(updatedArrival, manifestPax(25, visa) ++
          manifestPax(26, euPassport) ++
          manifestPax(25, nonVisa))
      )))

      offerAndWait(crunch.manifestsLiveInput, voyageManifests)
      offerAndCheckResult(Seq(updatedArrival), Seq(Queues.NonEeaDesk))
      success
    }

    "When I send another updated act pax & estimated time & manifest" >> {
      val updatedArrival = arrivalOne.copy(
        Estimated = Option(SDate("2019-01-01T00:25").millisSinceEpoch),
        ActPax = Option(35)
      )
      val voyageManifests = ManifestsFeedSuccess(DqManifests("", Set(
        manifestForArrival(updatedArrival,
          manifestPax(7,visa) ++
          manifestPax(7,euPassport) ++
          manifestPax(7,euPassport) ++
          manifestPax(7,euPassport) ++
          manifestPax(7,nonVisa))
      )))

      offerAndWait(crunch.manifestsLiveInput, voyageManifests)
      offerAndCheckResult(Seq(updatedArrival), Seq(Queues.NonEeaDesk))
      success
    }

    "When I send a second arrival with a manifest" >> {
      val updatedArrival = arrivalOne.copy(
        Estimated = Option(SDate("2019-01-01T00:25").millisSinceEpoch),
        ActPax = Option(211)
      )
      val voyageManifests = ManifestsFeedSuccess(DqManifests("", Set(
        manifestForArrival(updatedArrival,
          manifestPax(42, visa) ++
          manifestPax(127, euPassport) ++
          manifestPax(42, nonVisa)),
        manifestForArrival(arrivalTwo, manifestPax(110, euIdCard) ++ manifestPax(7, nonVisa))
      )))

      offerAndWait(crunch.manifestsLiveInput, voyageManifests)
      offerAndCheckResult(Seq(updatedArrival, arrivalTwo), Seq(Queues.NonEeaDesk))
      success
    }

    "When I send a second arrival with a manifest" >> {
      val updatedArrivalOne = arrivalOne.copy(
        Estimated = Option(SDate("2019-01-01T00:03").millisSinceEpoch),
        ActPax = Option(401)
      )
      val updatedArrivalTwo = arrivalTwo.copy(
        Estimated = Option(SDate("2019-01-01T00:03").millisSinceEpoch),
        ActPax = Option(176)
      )
      val voyageManifests = ManifestsFeedSuccess(DqManifests("", Set(
        manifestForArrival(updatedArrivalOne, manifestPax(300, visa) ++ manifestPax(99, euPassport) ++ manifestPax(2, nonVisa)),
        manifestForArrival(updatedArrivalTwo, manifestPax(30, euIdCard) ++ manifestPax( 30, nonVisa) ++ manifestPax(116, euPassport))
      )))

      offerAndWait(crunch.manifestsLiveInput, voyageManifests)
      offerAndCheckResult(Seq(updatedArrivalOne, updatedArrivalTwo), Seq(Queues.NonEeaDesk))
      success
    }
  }

  private def manifestForArrival(updatedArrival: Arrival, paxInfos: List[PassengerInfoJson]) = {
    val schDateTime = SDate(updatedArrival.Scheduled)
    VoyageManifest(EventTypes.CI, PortCode("STN"), updatedArrival.Origin, updatedArrival.voyageNumber, updatedArrival.carrierCode, ManifestDateOfArrival(schDateTime.toISODateOnly), ManifestTimeOfArrival(schDateTime.toHoursAndMinutes()), paxInfos)
  }

  private def offerAndCheckResult(arrivals: Seq[Arrival], queues: Seq[Queue] = Seq()): Unit = {
    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(arrivals)))

    crunch.portStateTestProbe.fishForMessage(5 second) {
      case ps: PortState => paxLoadsAreCorrect(ps, arrivals, queues)
    }
  }

  private def paxLoadsAreCorrect(ps: PortState, arrivals: Seq[Arrival], queues: Seq[Queue]): Boolean = {
    val flights = ps.flights
    val crunchMins = ps.crunchMinutes

    val arrivalsExist = arrivals.foldLeft(true) { case (soFar, a) => soFar && flights.contains(a.unique) }

    val arrivalsPaxTotal = arrivals.map {
      _.ActPax.getOrElse(-1)
    }.sum

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

    val earliestPcpMinuteAcrossArrivals = arrivals.foldLeft(Long.MaxValue) {
      case (earliestSoFar, a) => pcpForFlightFromBest(a).millisSinceEpoch match {
        case pcp if pcp < earliestSoFar => pcp
        case _ => earliestSoFar
      }
    }

    val firstPaxMinuteEqualsPcpTime = firstPaxLoadMinute == earliestPcpMinuteAcrossArrivals
    val paxLoadEqualsActPax = paxLoadTotal == arrivalsPaxTotal

    arrivalsExist && firstPaxMinuteEqualsPcpTime && paxLoadEqualsActPax && queuesOk
  }
}
