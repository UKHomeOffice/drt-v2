package uk.gov.homeoffice.drt.service

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.db.serialisers.EgateEligibility
import uk.gov.homeoffice.drt.models.{ManifestLike, ManifestPassengerProfile, PaxTypeAllocator, UniqueArrivalKey}
import uk.gov.homeoffice.drt.ports.PaxTypes._
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

object EgateSimulations {
  private val log = LoggerFactory.getLogger(getClass)

  private val feedSourceOrder: List[FeedSource] = List(LiveFeedSource, ApiFeedSource, ForecastFeedSource, HistoricApiFeedSource, AclFeedSource)

  def bxAndDrtStatsForDate(bxEgateTotalAndPercentageForDateAndTerminal: (UtcDate, Terminal) => Future[(Int, Double)],
                           drtEgateEligibleAndActualPercentageForDateAndTerminal: (UtcDate, Terminal) => Future[(Int, Double, Double)],
                           bxUptakePct: (Double, Double) => Double,
                          )
                          (implicit ec: ExecutionContext): (UtcDate, Terminal) => Future[(Int, Double, Double, Int, Double)] =
    (date, terminal) => {
      for {
        (bxTotalPax, bxEgatePct) <- bxEgateTotalAndPercentageForDateAndTerminal(date, terminal)
        (drtTotalPax, eligiblePct, drtEgatePercentage) <- drtEgateEligibleAndActualPercentageForDateAndTerminal(date, terminal)
        bxUptakePercentage = bxUptakePct(eligiblePct, bxEgatePct)
      } yield {
        println(s"bxEgatePercentage: $bxEgatePct, eligiblePct: $eligiblePct, drtEgatePercentage: $drtEgatePercentage")
        (bxTotalPax, bxEgatePct, bxUptakePercentage, drtTotalPax, drtEgatePercentage)
      }
    }

  def bxTotalPaxAndEgatePctForDateAndTerminal(bxQueueTotalsForPortAndDate: (UtcDate, Terminal) => Future[Map[Queue, Int]],
                                             )
                                             (implicit ec: ExecutionContext): (UtcDate, Terminal) => Future[(Int, Double)] =
    (date, terminal) => {
      bxQueueTotalsForPortAndDate(date, terminal)
        .map { queueTotals =>
          val egatePax = queueTotals.getOrElse(EGate, 0)
          val totalPax = queueTotals.values.sum
          val egatePct = if (totalPax > 0) (egatePax.toDouble / totalPax) * 100.0
          else 0.0
          (totalPax, egatePct)
        }
    }

  def drtEgateEligibleAndActualPercentageForDateAndTerminal(uptakePct: Double)
                                                           (cachedEgateEligibleAndUnderAgeForDate: (UtcDate, Terminal) => Future[Option[EgateEligibility]],
                                                            drtTotalPaxEgateEligiblePctAndUnderAgePctForDate: (UtcDate, Terminal) => Future[(Int, Double, Double)],
                                                            netEligiblePercentage: (Double, Double) => Double,
                                                           )
                                                           (implicit ec: ExecutionContext): (UtcDate, Terminal) => Future[(Int, Double, Double)] =
    (date, terminal) => {
      cachedEgateEligibleAndUnderAgeForDate(date, terminal)
        .flatMap {
          case Some(EgateEligibility(_, _, _, totalPax, egateEligiblePct, egateUnderAgePax, _)) =>
            Future.successful((totalPax, egateEligiblePct, egateUnderAgePax))
          case None =>
            drtTotalPaxEgateEligiblePctAndUnderAgePctForDate(date, terminal)
        }
        .map {
          case (totalPax, eligiblePct, underagePct) =>
            val netEligiblePct = netEligiblePercentage(eligiblePct, underagePct)
            val egatePaxPct = (netEligiblePct / 100d) * uptakePct
            (totalPax, netEligiblePct, egatePaxPct)
        }
    }

  def drtTotalPaxEgateEligiblePctAndUnderAgePctForDate(arrivalsWithManifestsForDateAndTerminal: (UtcDate, Terminal) => Future[Seq[(Arrival, Option[ManifestLike])]],
                                                       egateEligibleAndUnderAgePct: Seq[ManifestPassengerProfile] => (Double, Double),
                                                       storeEgateEligibleAndUnderAgeForDate: (UtcDate, Terminal, Int, Double, Double) => Unit,
                                                      )
                                                      (implicit ec: ExecutionContext): (UtcDate, Terminal) => Future[(Int, Double, Double)] = {
    (date, terminal) =>
      arrivalsWithManifestsForDateAndTerminal(date, terminal)
        .map { arrivalsWithManifests =>
          val arrivalPaxCounts = arrivalsWithManifests.collect {
            case (arrival, Some(manifest)) if manifest.uniquePassengers.nonEmpty =>
              val totalPcpPax = arrival.bestPaxEstimate(feedSourceOrder).getPcpPax.getOrElse(0)
              val (egatePaxPct, egateUnderAgePct) = egateEligibleAndUnderAgePct(manifest.uniquePassengers)
              (totalPcpPax, (egatePaxPct / 100 * totalPcpPax).round.toInt, (egateUnderAgePct / 100 * totalPcpPax).round.toInt)
          }
          val actualTotalPax = arrivalsWithManifests.map(_._1.bestPcpPaxEstimate(feedSourceOrder).getOrElse(0)).sum
          val manifestTotalPax = arrivalPaxCounts.map(_._1).sum
          val eligible = arrivalPaxCounts.map(_._2).sum.toDouble / manifestTotalPax * 100
          val underage = arrivalPaxCounts.map(_._3).sum.toDouble / manifestTotalPax * 100

          if (date < SDate.now().toUtcDate) {
            log.info(s"Storing historic egate eligibility for $date and $terminal: total=$manifestTotalPax, eligible=$eligible, underage=$underage")
            storeEgateEligibleAndUnderAgeForDate(date, terminal, actualTotalPax, eligible, underage)
          }

          (actualTotalPax, eligible, underage)
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
                case Some(manifest) if manifestOk(manifest, arrival) =>
                  liveCount = liveCount + 1
                  Future.successful((arrival, Some(manifest)))
                case _ =>
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

  private def manifestOk(manifest: ManifestLike, arrival: Arrival): Boolean = {
    val uniquePax = manifest.uniquePassengers.size
    val feedPax = arrival.bestPaxEstimate(feedSourceOrder).passengers.actual.getOrElse(0)
    if (uniquePax > 0 && feedPax > 0) {
      val ratio = uniquePax.toDouble / feedPax
      ratio < 1.05 && ratio > 0.95
    }
    else false
  }

  def bxUptakePct(eligiblePercentage: Double, egatePaxPercentage: Double): Double =
    if (eligiblePercentage > 0) {
      val uptake = egatePaxPercentage / eligiblePercentage * 100
      if (uptake > 100) 100 else uptake
    }
    else 0.0

  private val egateTypes = Seq(GBRNational, EeaMachineReadable, B5JPlusNational)
  private val egateUnderAgeTypes = Seq(GBRNationalBelowEgateAge, EeaBelowEGateAge, B5JPlusNationalBelowEGateAge)

  def egateEligibleAndUnderAgePct(paxTypeAllocator: PaxTypeAllocator): Seq[ManifestPassengerProfile] => (Double, Double) =
    passengerProfiles => {
      val totalPax = passengerProfiles.size
      val paxTypes = passengerProfiles.map(p => paxTypeAllocator(p))
      val egateEligibleCount = paxTypes.count(egateTypes.contains)
      val egateBelowAgeCount = paxTypes.count(egateUnderAgeTypes.contains)

      (egateEligibleCount.toDouble / totalPax * 100, egateBelowAgeCount.toDouble / totalPax * 100)
    }

  def netEgateEligiblePct(adultChildRatio: Double)(egateEligiblePct: Double, egateUnderAgePct: Double): Double = {
    val accompanyingAdultsPct = adultChildRatio * egateUnderAgePct
    val finalEgateEligiblePct = egateEligiblePct - accompanyingAdultsPct
    println(s"Egate Eligible %: $egateEligiblePct, Egate Under Age: $egateUnderAgePct, ratio: $adultChildRatio. Final eligible %: $finalEgateEligiblePct")

    finalEgateEligiblePct
  }
}
