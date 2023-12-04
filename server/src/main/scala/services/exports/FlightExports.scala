package services.exports

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.ArrivalKey
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.api.{AgeRange, FlightManifestSummary, UnknownAge}
import manifests.passengers.PassengerInfo
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.LocalDateStream
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, ArrivalExportHeadings}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSource
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode, PortRegion}
import uk.gov.homeoffice.drt.splits.ApiSplitsToSplitRatio
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

object FlightExports {
  private val splitSources = List(ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage)

  def dateAndFlightsToCsvRows(port: PortCode,
                              terminal: Terminal,
                              paxFeedSourceOrder: List[FeedSource],
                              manifestsProvider: LocalDate => Future[VoyageManifests],
                             )
                             (implicit ec: ExecutionContext): (LocalDate, Seq[ApiFlightWithSplits]) => Future[Seq[String]] = {
    val toCsv = FlightExports.flightsToCsvRows(port, terminal, paxFeedSourceOrder, manifestsProvider)
    (date, flights) => toCsv(date, flights)
  }

  private def flightsToCsvRows(port: PortCode,
                               terminal: Terminal,
                               paxFeedSourceOrder: List[FeedSource],
                               manifestsProvider: LocalDate => Future[VoyageManifests],
                              )
                              (implicit ec: ExecutionContext): (LocalDate, Seq[ApiFlightWithSplits]) => Future[Seq[String]] = {
    val regionName = PortRegion.fromPort(port).name
    val portName = port.iata
    val terminalName = terminal.toString
    val toRow = flightWithSplitsToCsvFields(paxFeedSourceOrder)
    (localDate, flights) => {
      manifestsProvider(localDate).map { vms =>
        flights
          .sortBy(_.apiFlight.PcpTime.getOrElse(0L))
          .map { fws =>
            val flightPart = toRow(fws.apiFlight).mkString(",")
            val invalidApi = apiIsInvalid(fws)
            val splitsPart = splitsForSources(fws, paxFeedSourceOrder).mkString(",")
            val apiPart = actualAPISplitsForFlightInHeadingOrder(fws, ArrivalExportHeadings.actualApiHeadings.split(",")).map(_.toString).mkString(",")
            val maybeManifest = vms.manifests.find(_.maybeKey.exists(_ == ArrivalKey(fws.apiFlight)))
            val maybePaxSummary = maybeManifest.flatMap(PassengerInfo.manifestToFlightManifestSummary)
            val natsSummary = s""""${nationalitiesFromSummary(maybePaxSummary)}""""
            val agesSummary = s""""${ageRangesFromSummary(maybePaxSummary)}""""
            s"$regionName,$portName,$terminalName,$flightPart,$invalidApi,$splitsPart,$apiPart,$natsSummary,$agesSummary\n"
          }
      }
    }
  }

  private val relevantFlight: (LocalDate, Seq[ApiFlightWithSplits]) => Seq[ApiFlightWithSplits] =
    (localDate, flights) =>
      flights.filter { fws =>
        val pcpStart = SDate(fws.apiFlight.PcpTime.getOrElse(0L))
        pcpStart.toLocalDate == localDate
      }

  def flightsForLocalDateRangeProvider(utcFlightsProvider: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
                                      ): (LocalDate, LocalDate) => Source[(LocalDate, Seq[ApiFlightWithSplits]), NotUsed] =
    LocalDateStream(utcFlightsProvider, startBufferDays = 1, endBufferDays = 2, transformData = relevantFlight)

  def manifestsForLocalDateProvider(utcProvider: (UtcDate, UtcDate) => Source[(UtcDate, VoyageManifests), NotUsed])
                                   (implicit ec: ExecutionContext, mat: Materializer): LocalDate => Future[VoyageManifests] =
    date => {
      val startUtc = SDate(date).toUtcDate
      val endUtc = SDate(date).addDays(1).addMinutes(-1).toUtcDate
      utcProvider(startUtc, endUtc)
        .runWith(Sink.seq)
        .map { seq =>
          val manifests = seq.flatMap(_._2.manifests.filter(vm => {
            vm.scheduled.toLocalDate == date
          }))
          VoyageManifests(manifests)
        }
    }

  def flightWithSplitsToCsvFields(paxFeedSourceOrder: Seq[FeedSource]): Arrival => List[String] =
    arrival => List(
      arrival.flightCodeString,
      arrival.flightCodeString,
      arrival.Origin.toString,
      arrival.Gate.getOrElse("") + "/" + arrival.Stand.getOrElse(""),
      arrival.displayStatus.description,
      millisToLocalDateTimeString(arrival.Scheduled),
      arrival.predictedTouchdown.map(p => millisToLocalDateTimeString(p)).getOrElse(""),
      arrival.Estimated.map(millisToLocalDateTimeString(_)).getOrElse(""),
      arrival.Actual.map(millisToLocalDateTimeString(_)).getOrElse(""),
      arrival.EstimatedChox.map(millisToLocalDateTimeString(_)).getOrElse(""),
      arrival.ActualChox.map(millisToLocalDateTimeString(_)).getOrElse(""),
      arrival.differenceFromScheduled.map(_.toMinutes.toString).getOrElse(""),
      arrival.PcpTime.map(millisToLocalDateTimeString(_)).getOrElse(""),
      arrival.bestPaxEstimate(paxFeedSourceOrder).passengers.actual.map(_.toString).getOrElse(""),
      arrival.bestPcpPaxEstimate(paxFeedSourceOrder).map(_.toString).getOrElse(""),
    )

  def apiIsInvalid(fws: ApiFlightWithSplits): String =
    if (fws.hasApi && !fws.hasValidApi) "Y" else ""

  def millisToLocalDateTimeString: MillisSinceEpoch => String =
    (millis: MillisSinceEpoch) => SDate(millis, Crunch.europeLondonTimeZone).toLocalDateTimeString

  def splitsForSources(fws: ApiFlightWithSplits,
                       paxFeedSourceOrder: List[FeedSource],
                      ): List[String] =
    splitSources.flatMap((ss: SplitSource) => queueSplits(ArrivalExportHeadings.queueNamesInOrder, fws, ss, paxFeedSourceOrder))

  private def queueSplits(queueNames: Seq[Queue],
                          fws: ApiFlightWithSplits,
                          splitSource: SplitSource,
                          paxFeedSourceOrder: List[FeedSource],
                         ): Seq[String] =
    queueNames.map(q => s"${queuePaxForFlightUsingSplits(fws, splitSource, paxFeedSourceOrder).getOrElse(q, "")}")

  private def queuePaxForFlightUsingSplits(fws: ApiFlightWithSplits,
                                           splitSource: SplitSource,
                                           paxFeedSourceOrder: List[FeedSource],
                                          ): Map[Queue, Int] =
    fws
      .splits
      .find(_.source == splitSource)
      .map(splits => ApiSplitsToSplitRatio.flightPaxPerQueueUsingSplitsAsRatio(splits, fws, paxFeedSourceOrder))
      .getOrElse(Map())

  def actualAPISplitsForFlightInHeadingOrder(flight: ApiFlightWithSplits, headings: Iterable[String]): Iterable[Double] =
    headings.map(h => Exports.actualAPISplitsAndHeadingsFromFlight(flight).toMap
      .getOrElse(h, 0.0))
      .map(n => Math.round(n).toDouble)

  def nationalitiesFromSummary(maybeSummary: Option[FlightManifestSummary]): String =
    maybeSummary.map {
      _.nationalities
        .toList
        .sortBy { case (nat, paxCount) =>
          f"$paxCount%03d-${nat.code.getBytes.map(265 - _).mkString("-")}"
        }
        .reverseMap {
          case (nat, pax) => s"${nat.toString()}:$pax"
        }
        .mkString(",")
    }.getOrElse("")

  def ageRangesFromSummary(maybeSummary: Option[FlightManifestSummary]): String =
    maybeSummary.map {
      _.ageRanges
        .toList
        .sortBy {
          case (AgeRange(bottom, _), _) => bottom
          case (UnknownAge, _) => 1000
        }
        .map {
          case (ageRange, pax) => s"${ageRange.title}:$pax"
        }
        .mkString(",")
    }.getOrElse("")
}
