package drt.shared

object SplitRatiosNs {
//  type SplitRatios = List[SplitRatio]
  case class SplitRatios(splits: List[SplitRatio]=Nil, origin: String)

  object SplitSources {
    val AdvPaxInfo = "advPaxInfo"
    val ApiSplitsWithCsvPercentage = "ApiSplitsWithHistoricalEGatePercentage"
    val Historical = "Historical"
    val TerminalAverage = "TerminalAverage"
  }

  object SplitRatios {
    def apply(origin: String, ratios: SplitRatio*): SplitRatios = SplitRatios(ratios.toList, origin)
    def apply(origin: String, ratios: List[SplitRatio]): SplitRatios = SplitRatios(ratios.toList, origin)
  }
  case class SplitRatio(paxType: PaxTypeAndQueue, ratio: Double)
}
