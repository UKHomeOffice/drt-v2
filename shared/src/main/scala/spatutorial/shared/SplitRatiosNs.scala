package spatutorial.shared

object SplitRatiosNs {
//  type SplitRatios = List[SplitRatio]
  case class SplitRatios(splits: List[SplitRatio])

  case class SplitRatio(paxType: PaxTypeAndQueue, ratio: Double)
}
