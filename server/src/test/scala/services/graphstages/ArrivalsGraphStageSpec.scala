package services.graphstages

import controllers.ArrivalGenerator
import drt.shared.PortCode
import drt.shared.Terminals.{T1, T2}
import org.specs2.mutable.Specification

class ArrivalsGraphStageSpec extends Specification {
  "terminalRemovals" should {
    val arrivalT1 = ArrivalGenerator.arrival("BA0001", terminal = T1, schDt = "2021-05-01T12:50", origin = PortCode("JFK"))
    val arrivalT2 = arrivalT1.copy(Terminal = T2)
    "give no removals given no arrivals" >> {
      val removals = ArrivalsGraphStage.terminalRemovals(Seq(), Seq())
      removals === Iterable.empty
    }
    "give no removals given a new arrival" >> {
      val removals = ArrivalsGraphStage.terminalRemovals(Seq(arrivalT1), Seq())
      removals === Iterable.empty
    }
    "give no removals given no incoming, but one existing arrival" >> {
      val removals = ArrivalsGraphStage.terminalRemovals(Seq(), Seq(arrivalT1))
      removals === Iterable.empty
    }
    "give no removals given no changes" >> {
      val removals = ArrivalsGraphStage.terminalRemovals(Seq(arrivalT1), Seq(arrivalT1))
      removals === Iterable.empty
    }
    "give 1 removal given an arrival at T1 which was previously at T2" >> {
      val removals = ArrivalsGraphStage.terminalRemovals(Seq(arrivalT1), Seq(arrivalT2))
      removals === Iterable(arrivalT2)
    }
    "give 1 removal given an arrival at T2 which was previously at T1" >> {
      val removals = ArrivalsGraphStage.terminalRemovals(Seq(arrivalT2), Seq(arrivalT1))
      removals === Iterable(arrivalT1)
    }
  }
}
