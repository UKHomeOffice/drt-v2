package services.crunch

import controllers.ArrivalGenerator
import drt.shared.{Arrival, DqEventCodes, Queues}
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest}
import server.feeds.{ArrivalsFeedSuccess, ManifestsFeedSuccess}
import services.SDate
import services.crunch.VoyageManifestGenerator.{euPassport, nonVisa, visa, euIdCard}
import services.graphstages.DqManifests

import scala.collection.immutable.List
import scala.concurrent.duration._

class ArrivalUpdatesCorrectlyAffectLoads extends CrunchTestLike {
  val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(
    now = () => SDate("2019-01-01T01:00"),
    pcpArrivalTime = pcpForFlightFromBest,
    airportConfig = airportConfig.copy(
      queues = Map("T1" -> Seq(Queues.EeaDesk, Queues.NonEeaDesk, Queues.EGate), "T2" -> Seq())
    )
  )
  val arrivalOne: Arrival = ArrivalGenerator.arrival(
    iata = "BA0001",
    terminal = "T1",
    origin = "JFK",
    schDt = "2019-01-01T00:00",
    actPax = Option(100)
  )

  val arrivalTwo: Arrival = ArrivalGenerator.arrival(
    iata = "BA0002",
    terminal = "T1",
    origin = "JFK",
    schDt = "2019-01-01T00:05",
    actPax = Option(117)
  )

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
        manifestForArrival(updatedArrival, List(visa, euPassport, nonVisa))
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
        manifestForArrival(updatedArrival, List(visa, euPassport, euPassport, euPassport, nonVisa))
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
        manifestForArrival(updatedArrival, List(visa, euPassport, euPassport, euPassport, nonVisa)),
        manifestForArrival(arrivalTwo, List(euIdCard, nonVisa))
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
        manifestForArrival(updatedArrivalOne, List(visa, visa, visa, euPassport, euPassport, euPassport, nonVisa)),
        manifestForArrival(updatedArrivalTwo, List(euIdCard, nonVisa, nonVisa, nonVisa, euPassport))
      )))

      offerAndWait(crunch.manifestsLiveInput, voyageManifests)
      offerAndCheckResult(Seq(updatedArrivalOne, updatedArrivalTwo), Seq(Queues.NonEeaDesk))
      success
    }
  }

  private def manifestForArrival(updatedArrival: Arrival, paxInfos: List[PassengerInfoJson]) = {
    val schDateTime = SDate(updatedArrival.Scheduled)
    VoyageManifest(DqEventCodes.CheckIn, "STN", updatedArrival.Origin, f"${updatedArrival.flightNumber}%04d", updatedArrival.carrierCode, schDateTime.toISODateOnly, schDateTime.toHoursAndMinutes(), paxInfos)
  }

  private def offerAndCheckResult(arrivals: Seq[Arrival], queues: Seq[String] = Seq()): Unit = {
    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(arrivals)))

    crunch.liveTestProbe.fishForMessage(5 second) {
      case ps: PortState => paxLoadsAreCorrect(ps, arrivals, queues)
    }
  }

  private def paxLoadsAreCorrect(ps: PortState, arrivals: Seq[Arrival], queues: Seq[String]): Boolean = {
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
          case (okSoFar, q) => okSoFar && nonZeroLoadMinutes.map(_.queueName).contains(q)
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
