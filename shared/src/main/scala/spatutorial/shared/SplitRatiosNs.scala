package spatutorial.shared

object SplitRatiosNs {
//  type SplitRatios = List[SplitRatio]
  case class SplitRatios(splits: List[SplitRatio]=Nil)

  object SplitRatios {
    def apply(ratios: SplitRatio*): SplitRatios = SplitRatios(ratios.toList)
  }
  case class SplitRatio(paxType: PaxTypeAndQueue, ratio: Double)
}
