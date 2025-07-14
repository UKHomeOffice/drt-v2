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

  def bxAndDrtEgatePctAndBxUptakePctForDate(bxEgatePercentageForDateAndTerminal: (UtcDate, Terminal) => Future[Double],
                                            drtEgateEligibleAndActualPercentageForDateAndTerminal: (UtcDate, Terminal) => Future[(Double, Double)],
                                            bxUptakePct: (Double, Double) => Double,
                                           )
                                           (implicit ec: ExecutionContext): (UtcDate, Terminal) => Future[(Double, Double, Double)] =
    (date, terminal) => {
      for {
        bxEgatePercentage <- bxEgatePercentageForDateAndTerminal(date, terminal)
        (eligiblePct, drtEgatePercentage) <- drtEgateEligibleAndActualPercentageForDateAndTerminal(date, terminal)
        bxUptakePercentage = bxUptakePct(eligiblePct, bxEgatePercentage)
      } yield {
        println(s"bxEgatePercentage: $bxEgatePercentage, eligiblePct: $eligiblePct, drtEgatePercentage: $drtEgatePercentage")
        (bxEgatePercentage, drtEgatePercentage, bxUptakePercentage)
      }
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

  def drtEgateEligibleAndActualPercentageForDateAndTerminal(uptakePct: Double)
                                                           (arrivalsWithManifestsForDateAndTerminal: (UtcDate, Terminal) => Future[Seq[(Arrival, Option[ManifestLike])]],
                                                            egateEligibleAndUnderAgeForDate: (UtcDate, Terminal) => Future[Option[EgateEligibility]],
                                                            storeEgateEligibleAndUnderAgeForDate: (UtcDate, Terminal, Int, Int, Int) => Unit,
                                                            egateEligibleAndUnderAgePercentages: Seq[ManifestPassengerProfile] => (Double, Double),
                                                            eligiblePercentage: (Int, Int, Int) => Double,
                                                           )
                                                           (implicit ec: ExecutionContext): (UtcDate, Terminal) => Future[(Double, Double)] =
    (date, terminal) => {
      egateEligibleAndUnderAgeForDate(date, terminal)
        .flatMap {
          case Some(EgateEligibility(_, _, _, totalPax, egateEligible, egateUnderAge, _)) =>
            Future.successful((totalPax, egateEligible, egateUnderAge))
          case None =>
            arrivalsWithManifestsForDateAndTerminal(date, terminal)
              .map { arrivalsWithManifests =>
                val arrivalPaxCounts: Seq[(Int, Int, Int)] = arrivalsWithManifests.collect {
                  case (arrival, Some(manifest)) if manifest.uniquePassengers.nonEmpty =>
                    val totalPcpPax = arrival.bestPaxEstimate(feedSourceOrder).getPcpPax.getOrElse(0)
                    val (egatePaxPct, egateUnderAgePct) = egateEligibleAndUnderAgePercentages(manifest.uniquePassengers)
                    (totalPcpPax, (egatePaxPct / 100 * totalPcpPax).round.toInt, (egateUnderAgePct / 100 * totalPcpPax).round.toInt)
                }
                val total = arrivalPaxCounts.map(_._1).sum
                val eligible = arrivalPaxCounts.map(_._2).sum
                val underage = arrivalPaxCounts.map(_._3).sum

                if (date < SDate.now().toUtcDate) {
                  log.info(s"Storing historic egate eligibility for $date and $terminal: total=$total, eligible=$eligible, underage=$underage")
                  storeEgateEligibleAndUnderAgeForDate(date, terminal, total, eligible, underage)
                }

                (total, eligible, underage)
              }
        }
        .map {
          case (total, eligible, underage) =>
            val eligiblePct = eligiblePercentage(total, eligible, underage)
            val egatePaxPct = (eligiblePct / 100d) * uptakePct
            (eligiblePct, egatePaxPct)
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
      val uptake = egatePaxPercentage / eligiblePercentage
      if (uptake > 1.0) 1.0 else uptake
    }
    else 0.0

  private val egateTypes = Seq(GBRNational, EeaMachineReadable, B5JPlusNational)
  private val egateUnderAgeTypes = Seq(GBRNationalBelowEgateAge, EeaBelowEGateAge, B5JPlusNationalBelowEGateAge)

  def egateEligibleAndUnderAgePercentages(paxTypeAllocator: PaxTypeAllocator): Seq[ManifestPassengerProfile] => (Double, Double) =
    passengerProfiles => {
      val totalPax = passengerProfiles.size
      val paxTypes = passengerProfiles.map(p => paxTypeAllocator(p))
      val egateEligibleCount = paxTypes.count(egateTypes.contains)
      val egateBelowAgeCount = paxTypes.count(egateUnderAgeTypes.contains)

      (egateEligibleCount.toDouble / totalPax * 100, egateBelowAgeCount.toDouble / totalPax * 100)
    }

  //  def egateEligiblePercentage_(childParentRatio: Double)(counts: Seq[(Int, Int, Int)]): Double = {
  //    val totalPax = counts.map(_._1).sum
  //    val totalEgateUnderAge = counts.map(_._3).sum
  //    val parentForKids = (totalEgateUnderAge.toDouble * childParentRatio).round
  //    val totalEgateEligibles = counts.map(_._2).sum - parentForKids
  //    println(s"Total Pax: $totalPax, Total Egate Eligibles: $totalEgateEligibles, Total Egate Under Age: $totalEgateUnderAge, Parent For Kids: $parentForKids")
  //    val egateEligiblePercentage = if (totalPax > 0) (totalEgateEligibles.toDouble / totalPax) * 100.0 else 0.0
  //    egateEligiblePercentage
  //  }

  def egateEligiblePercentage(childParentRatio: Double)(totalPax: Int, egateEligiblePax: Int, egateUnderAgePax: Int): Double = {
    val parentForKids = (egateUnderAgePax.toDouble * childParentRatio).round
    val totalEgateEligibles = egateEligiblePax - parentForKids
    println(s"Total Pax: $totalPax, Total Egate Eligibles: $totalEgateEligibles, Total Egate Under Age: $egateUnderAgePax, Parent For Kids: $parentForKids")
    val egateEligiblePercentage = if (totalPax > 0) (totalEgateEligibles.toDouble / totalPax) * 100.0 else 0.0
    egateEligiblePercentage
  }
}
