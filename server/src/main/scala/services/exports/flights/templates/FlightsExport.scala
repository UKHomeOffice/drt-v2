package services.exports.flights.templates

import actors.PartitionedPortStateActor.FlightsRequest
import akka.NotUsed
import akka.stream.scaladsl.Source
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.Queue
import drt.shared.SplitRatiosNs.SplitSource
import drt.shared.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage}
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import drt.shared.splits.ApiSplitsToSplitRatio
import drt.shared.{ApiFlightWithSplits, CodeShares, PaxTypesAndQueues, SDateLike}
import org.joda.time.DateTimeZone
import services.SDate
import services.exports.Exports
import services.graphstages.Crunch

trait FlightsExport {

  val timeZone: DateTimeZone = Crunch.europeLondonTimeZone

  def headings: String

  def rowValues(fws: ApiFlightWithSplits): Seq[String]

  def start: SDateLike

  def end: SDateLike

  def terminal: Terminal

  val request: FlightsRequest

  val queueNames: Seq[Queue] = ApiSplitsToSplitRatio.queuesFromPaxTypeAndQueue(PaxTypesAndQueues.inOrder)

  def flightToCsvRow(fws: ApiFlightWithSplits): String = rowValues(fws).mkString(",")

  def millisToDateStringFn: MillisSinceEpoch => String = SDate.millisToLocalIsoDateOnly(timeZone)

  def millisToTimeStringFn: MillisSinceEpoch => String = SDate.millisToLocalHoursAndMinutes(timeZone)

  val splitSources = List(ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage)

  def queueSplits(queueNames: Seq[Queue],
                  fws: ApiFlightWithSplits,
                  splitSource: SplitSource): Seq[String] =
    queueNames.map(q => s"${queuePaxForFlightUsingSplits(fws, splitSource).getOrElse(q, "")}")

  def queuePaxForFlightUsingSplits(fws: ApiFlightWithSplits, splitSource: SplitSource): Map[Queue, Int] =
    fws
      .splits
      .find(_.source == splitSource)
      .map(splits => ApiSplitsToSplitRatio.flightPaxPerQueueUsingSplitsAsRatio(splits, fws))
      .getOrElse(Map())

  def actualAPISplitsForFlightInHeadingOrder(flight: ApiFlightWithSplits, headings: Seq[String]): Seq[Double] =
    headings.map(h => Exports.actualAPISplitsAndHeadingsFromFlight(flight).toMap.getOrElse(h, 0.0))
      .map(n => Math.round(n).toDouble)

  def csvStream(flightsStream: Source[FlightsWithSplits, NotUsed]): Source[String, NotUsed] =
    flightsStream
      .map(fws => fwsToCsv(fws, flightToCsvRow))
      .prepend(Source(List(headings + "\n")))


  def fwsToCsv(fws: FlightsWithSplits, csvRowFn: ApiFlightWithSplits => String): String =
    uniqueArrivalsWithCodeShares(fws.flights.values.toSeq)
      .sortBy {
        case (fws, _) => (fws.apiFlight.PcpTime, fws.apiFlight.VoyageNumber.numeric, fws.apiFlight.Origin.iata)
      }
      .map {
        case (fws, _) => csvRowFn(fws) + "\n"
      }
      .mkString

  val uniqueArrivalsWithCodeShares: Seq[ApiFlightWithSplits] => List[(ApiFlightWithSplits, Set[Arrival])] = CodeShares
    .uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => identity(f.apiFlight))

}
