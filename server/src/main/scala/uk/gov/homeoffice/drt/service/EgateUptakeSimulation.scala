package uk.gov.homeoffice.drt.service

import manifests.queues.SplitsCalculator
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.slf4j.LoggerFactory
import passengersplits.WholePassengerQueueSplits
import queueus.TerminalQueueAllocator
import services.crunch.CrunchSystem.paxTypeQueueAllocator
import uk.gov.homeoffice.drt.arrivals.SplitStyle.Ratio
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, Splits}
import uk.gov.homeoffice.drt.models.{ManifestLike, UniqueArrivalKey}
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, Open, Queue, QueueFallbacks}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.TerminalAverage
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.UtcDate

import scala.concurrent.{ExecutionContext, Future}

object EgateUptakeSimulation {
  private val log = LoggerFactory.getLogger(getClass)

  private val feedSourceOrder: List[FeedSource] = List(LiveFeedSource, ApiFeedSource, ForecastFeedSource, HistoricApiFeedSource, AclFeedSource)

  def bxAndDrtEgatePercentageForDate(bxEgatePercentageForDateAndTerminal: (UtcDate, Terminal) => Future[Double],
                                     drtEgatePercentageForDateAndTerminal: (UtcDate, Terminal) => Future[Double],
                                    )
                                    (implicit ec: ExecutionContext): (UtcDate, Terminal) => Future[(Double, Double)] =
    (date, terminal) => {
      for {
        bxEgatePercentage <- bxEgatePercentageForDateAndTerminal(date, terminal)
        drtEgatePercentage <- drtEgatePercentageForDateAndTerminal(date, terminal)
      } yield (bxEgatePercentage, drtEgatePercentage)
    }

  def bxEgatePercentageForDateAndTerminal(bxQueueTotalsForPortAndDate: (UtcDate, Terminal) => Future[Map[Queue, Int]],
                                         )
                                         (implicit ec: ExecutionContext): (UtcDate, Terminal) => Future[Double] =
    (date, terminal) => {
      bxQueueTotalsForPortAndDate(date, terminal)
        .map { queueTotals =>
          val egatePax = queueTotals.getOrElse(EGate, 0)
          val totalPax = queueTotals.values.sum
          if (totalPax > 0) (egatePax.toDouble / totalPax) * 100.0
          else 0.0
        }
    }

  def drtEgatePercentageForDateAndTerminal(flightsWithManifestsForDateAndTerminal: (UtcDate, Terminal) => Future[Seq[(Arrival, Option[ManifestLike])]],
                                           egateAndDeskPaxForFlight: (Arrival, Option[ManifestLike]) => (Int, Int),
                                          )
                                          (implicit ec: ExecutionContext): (UtcDate, Terminal) => Future[Double] =
    (date, terminal) => {
      flightsWithManifestsForDateAndTerminal(date, terminal)
        .map { flightsWithManifests =>
          val (egatePax, deskPax) = flightsWithManifests
            .map { case (flight, manifest) => egateAndDeskPaxForFlight(flight, manifest) }
            .foldLeft((0, 0)) { case ((egateAcc, deskAcc), (egate, desk)) => (egateAcc + egate, deskAcc + desk) }

          val totalPax = egatePax + deskPax

          if (totalPax > 0) (egatePax.toDouble / totalPax) * 100.0
          else 0.0
        }
    }

  def arrivalsWithManifestsForDateAndTerminal(portCode: PortCode,
                                              liveManifest: UniqueArrivalKey => Future[Option[ManifestLike]],
                                              historicManifest: UniqueArrivalKey => Future[Option[ManifestLike]],
                                              arrivalsForDateAndTerminal: (UtcDate, Terminal) => Future[Seq[Arrival]],
                                             )
                                             (implicit ec: ExecutionContext, mat: Materializer): (UtcDate, Terminal) => Future[Seq[(Arrival, Option[ManifestLike])]] =
    (date, terminal) => {
      arrivalsForDateAndTerminal(date, terminal)
        .flatMap { flights =>
          var liveCount = 0
          var historicCount = 0
          var terminalCount = 0
          Source(flights.filterNot(a => a.Origin.isDomesticOrCta || a.isCancelled))
            .mapAsync(1) { arrival =>
              val uniqueArrivalKey = UniqueArrivalKey(arrival, portCode)
              liveManifest(uniqueArrivalKey).flatMap {
                case Some(manifest) if Math.abs(manifest.uniquePassengers.size.toDouble / arrival.bestPcpPaxEstimate(feedSourceOrder).getOrElse(0)) < 0.05  =>
                  liveCount = liveCount + 1
                  Future.successful((arrival, Some(manifest)))
                case None =>
                  historicManifest(uniqueArrivalKey).map { historicManifest =>
                    if (historicManifest.isDefined) {
                      historicCount = historicCount + 1
                    } else {
                      terminalCount = terminalCount + 1
                    }
                    (arrival, historicManifest)
                  }
              }
            }
            .runWith(Sink.seq)
            .map { res =>
              log.info(s"Found ${res.size} flights for $date and $terminal: $liveCount live, $historicCount historic, $terminalCount terminal only")
              res
            }
        }
    }


  def egateAndDeskPaxForFlight(splitsCalculator: SplitsCalculator, fallbacks: QueueFallbacks): (Arrival, Option[ManifestLike]) => (Int, Int) = {
    (arrival, manifestOpt) => {
      val splits = manifestOpt
        .map(m => splitsCalculator.splitsForManifest(m, arrival.Terminal))
        .getOrElse(splitsCalculator.terminalSplits(arrival.Terminal).getOrElse(Splits(Set(), TerminalAverage, None, Ratio)))

      val totalPaxFromFeed = arrival.bestPaxEstimate(feedSourceOrder).getPcpPax.getOrElse(0)

      if (totalPaxFromFeed > 0) {
        val pcpRange = arrival.pcpRange(feedSourceOrder)
        val distributePaxOverQueuesAndMinutes = WholePassengerQueueSplits.wholePaxLoadsPerQueuePerMinute(pcpRange, (_, _) => 1d, (_, _) => Open)
        val workloadForFlight = WholePassengerQueueSplits.paxWorkloadsByQueue(distributePaxOverQueuesAndMinutes, fallbacks, feedSourceOrder, splitsCalculator.terminalSplits)
        val loads = workloadForFlight(ApiFlightWithSplits(arrival, Set(splits), None))

        val totalPax = loads.flatMap(_._2.map(_._2.size)).sum
        val egatePax = loads.getOrElse(EGate, Map.empty).values.map(_.size).sum
        val deskPax = totalPax - egatePax

        if (totalPax != totalPaxFromFeed) {
          log.warn(s"Total pax from splits $totalPax does not match total pax from flight $totalPaxFromFeed for flight ${arrival.unique}. Splits type is ${splits.splitStyle} ${splits.maybeEventType}: ${loads}")
        }

        (egatePax, deskPax)
      }
      else (0, 0)
    }
  }

  def splitsCalculatorForPaxAllocation(airportConfig: AirportConfig,
                                       paxTypeAllocation: Map[Terminal, Map[PaxType, Seq[(Queue, Double)]]],
                                    ): SplitsCalculator = {
    val paxQueueAllocator = paxTypeQueueAllocator(airportConfig.hasTransfer, TerminalQueueAllocator(paxTypeAllocation))
    SplitsCalculator(paxQueueAllocator, airportConfig.terminalPaxSplits)
  }

  def queueAllocationForEgateUptake(terminalPaxTypeQueueAllocation: Map[Terminal, Map[PaxType, Seq[(Queue, Double)]]],
                                    egateUptake: Double,
                                   ): Map[Terminal, Map[PaxType, Seq[(Queue, Double)]]] =
    terminalPaxTypeQueueAllocation.map { case (terminal, allocation) =>
      terminal -> allocation.map {
        case (paxType, queues) =>
          val hasEgateSplit = queues.exists { case (queue, split) => queue == EGate && split > 0.0 }
          if (hasEgateSplit)
            (paxType, List(EGate -> egateUptake, EeaDesk -> (1.0 - egateUptake)))
          else
            (paxType, queues)
      }
    }
}
