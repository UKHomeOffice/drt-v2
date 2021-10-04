package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.api.Arrival
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, ScenarioSimulationSource}
import upickle.default.{macroRW, _}

case class ApiFlightWithSplits(apiFlight: Arrival, splits: Set[Splits], lastUpdated: Option[MillisSinceEpoch] = None)
  extends WithUnique[UniqueArrival]
    with WithLastUpdated {

  def totalPaxFromApi: Option[Int] = splits.collectFirst {
    case splits if splits.source == ApiSplitsWithHistoricalEGateAndFTPercentages =>
      Math.round(splits.totalPax).toInt
  }

  def totalPaxFromApiExcludingTransfer: Option[Int] =
    splits.collectFirst { case splits if splits.source == ApiSplitsWithHistoricalEGateAndFTPercentages =>
      Math.round(splits.totalExcludingTransferPax).toInt
    }

  def pcpPaxEstimate: Int =
    totalPaxFromApiExcludingTransfer match {
      case Some(apiTotal) if hasValidApi => apiTotal
      case _ => apiFlight.bestPcpPaxEstimate
    }

  def totalPax: Option[Int] =
    if (hasValidApi) totalPaxFromApi
    else apiFlight.ActPax

  def equals(candidate: ApiFlightWithSplits): Boolean =
    this.copy(lastUpdated = None) == candidate.copy(lastUpdated = None)

  def bestSplits: Option[Splits] = {
    val apiSplitsDc = splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages)
    val scenarioSplits = splits.find(s => s.source == SplitSources.ScenarioSimulationSplits)
    val historicalSplits = splits.find(_.source == SplitSources.Historical)
    val terminalSplits = splits.find(_.source == SplitSources.TerminalAverage)

    val apiSplits: List[Option[Splits]] = if (hasValidApi) List(apiSplitsDc) else List(scenarioSplits)

    val splitsForConsideration: List[Option[Splits]] = apiSplits ::: List(historicalSplits, terminalSplits)

    splitsForConsideration.find {
      case Some(_) => true
      case _ => false
    }.flatten
  }

  val hasApi: Boolean = splits.exists(_.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages)

  def hasValidApi: Boolean = {
    val maybeApiSplits = splits.find(_.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages)
    val hasLiveSource = apiFlight.FeedSources.contains(LiveFeedSource)
    val hasSimulationSource = apiFlight.FeedSources.contains(ScenarioSimulationSource)
    (maybeApiSplits, hasLiveSource, hasSimulationSource) match {
      case (Some(_), _, true) => true
      case (Some(_), false, _) => true
      case (Some(api), true, _) if isWithinThreshold(api) => true
      case _ => false
    }
  }

  def isWithinThreshold(apiSplits: Splits): Boolean = apiFlight.ActPax.forall { actPax =>
    val apiPaxNo = apiSplits.totalExcludingTransferPax
    val threshold: Double = 0.05
    val portDirectPax: Double = actPax - apiFlight.TranPax.getOrElse(0)
    apiPaxNo != 0 && Math.abs(apiPaxNo - portDirectPax) / apiPaxNo < threshold
  }

  def hasPcpPaxIn(start: SDateLike, end: SDateLike): Boolean = apiFlight.hasPcpDuring(start, end)

  override val unique: UniqueArrival = apiFlight.unique
}

object ApiFlightWithSplits {
  implicit val rw: ReadWriter[ApiFlightWithSplits] = macroRW

  def fromArrival(arrival: Arrival): ApiFlightWithSplits = ApiFlightWithSplits(arrival, Set())
}
