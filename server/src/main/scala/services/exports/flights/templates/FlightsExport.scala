package services.exports.flights.templates

import actors.PartitionedPortStateActor.FlightsRequest
import akka.NotUsed
import akka.stream.scaladsl.Source
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.splits.ApiSplitsToSplitRatio
import drt.shared.{ArrivalKey, CodeShares}
import org.joda.time.DateTimeZone
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import services.exports.Exports
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, ArrivalExportHeadings, FlightsWithSplits}
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSource
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

trait FlightsExport {

  val timeZone: DateTimeZone = Crunch.europeLondonTimeZone

  def headings: String

  def rowValues(fws: ApiFlightWithSplits, maybeManifest: Option[VoyageManifest]): Iterable[String]

  def start: SDateLike

  def end: SDateLike

  def terminal: Terminal

  val request: FlightsRequest

  val standardFilter: (ApiFlightWithSplits, Terminal) => Boolean = (fws, terminal) => fws.apiFlight.Terminal == terminal

  val flightsFilter: (ApiFlightWithSplits, Terminal) => Boolean

  val paxFeedSourceOrder: List[FeedSource]

  private def flightToCsvRow(fws: ApiFlightWithSplits, maybeManifest: Option[VoyageManifest]): String = rowValues(fws, maybeManifest).mkString(",")

  private val splitSources = List(ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage)

  def splitsForSources(fws: ApiFlightWithSplits): List[String] =
    splitSources.flatMap((ss: SplitSource) => queueSplits(ArrivalExportHeadings.queueNamesInOrder, fws, ss))

  private def queueSplits(queueNames: Seq[Queue],
                          fws: ApiFlightWithSplits,
                          splitSource: SplitSource): Seq[String] =
    queueNames.map(q => s"${queuePaxForFlightUsingSplits(fws, splitSource).getOrElse(q, "")}")

  private def queuePaxForFlightUsingSplits(fws: ApiFlightWithSplits, splitSource: SplitSource): Map[Queue, Int] =
    fws
      .splits
      .find(_.source == splitSource)
      .map(splits => ApiSplitsToSplitRatio.flightPaxPerQueueUsingSplitsAsRatio(splits, fws, paxFeedSourceOrder))
      .getOrElse(Map())

  def actualAPISplitsForFlightInHeadingOrder(flight: ApiFlightWithSplits, headings: Iterable[String]): Iterable[Double] =
    headings.map(h => Exports.actualAPISplitsAndHeadingsFromFlight(flight).toMap
      .getOrElse(h, 0.0))
      .map(n => Math.round(n).toDouble)

  def csvStream(flightsStream: Source[(FlightsWithSplits, VoyageManifests), NotUsed]): Source[String, NotUsed] =
    filterAndSort(flightsStream)
      .map { case (fws, maybeManifest) =>
        flightToCsvRow(fws, maybeManifest) + "\n"
      }
      .prepend(Source(List(headings + "\n")))

  private def filterAndSort(flightsStream: Source[(FlightsWithSplits, VoyageManifests), NotUsed],
                           ): Source[(ApiFlightWithSplits, Option[VoyageManifest]), NotUsed] =
    flightsStream.mapConcat { case (flights, manifests) =>
      uniqueArrivalsWithCodeShares(flights.flights.values.toSeq)
        .filter(fws => flightsFilter(fws, terminal))
        .toSeq
        .sortBy(_.apiFlight.PcpTime.getOrElse(0L))
        .map { fws =>
          val maybeManifest = manifests.manifests.find(_.maybeKey.exists(_ == ArrivalKey(fws.apiFlight)))
          (fws, maybeManifest)
        }
    }

  val uniqueArrivalsWithCodeShares: Seq[ApiFlightWithSplits] => Iterable[ApiFlightWithSplits] = CodeShares
    .uniqueArrivals((f: ApiFlightWithSplits) => identity(f.apiFlight), paxFeedSourceOrder)

}
