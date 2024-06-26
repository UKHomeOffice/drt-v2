package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.ports.Terminals._
import drt.shared.redlist.{LhrRedListDates, LhrTerminalTypes}
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.redlist.RedListUpdates

import scala.collection.immutable.HashSet

class FlightDisplayFilterSpec extends Specification {

  object LhrRedListDatesImpl extends LhrRedListDates {
    override val t3RedListOpeningDate: MillisSinceEpoch = 500L
    override val t4RedListOpeningDate: MillisSinceEpoch = 1000L
    override val startRedListingDate: MillisSinceEpoch = 500L
    override val endRedListingDate: MillisSinceEpoch = 500L
  }

  private val beforeT4Opening: MillisSinceEpoch = LhrRedListDatesImpl.t4RedListOpeningDate - 10
  private val afterT4Opening: MillisSinceEpoch = LhrRedListDatesImpl.t4RedListOpeningDate + 10

  val redListOriginInBolivia = PortCode("VVI")
  val nonRedListOriginInFrance = PortCode("CDG")
  val redListPorts = HashSet(redListOriginInBolivia)
  val isRedListOrigin: (PortCode, MillisSinceEpoch, RedListUpdates) => Boolean = (pc, _, _) => redListPorts.contains(pc)

  val redListT2preT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = beforeT4Opening, origin = redListOriginInBolivia, terminal = T2).toArrival(LiveFeedSource), Set())
  val nonRedListT2preT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = beforeT4Opening, origin = nonRedListOriginInFrance, terminal = T2).toArrival(LiveFeedSource), Set())
  val redListT3preT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = beforeT4Opening, origin = redListOriginInBolivia, terminal = T3).toArrival(LiveFeedSource), Set())
  val nonRedListT3preT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = beforeT4Opening, origin = nonRedListOriginInFrance, terminal = T3).toArrival(LiveFeedSource), Set())
  val redListT4preT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = beforeT4Opening, origin = redListOriginInBolivia, terminal = T4).toArrival(LiveFeedSource), Set())
  val nonRedListT4preT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = beforeT4Opening, origin = nonRedListOriginInFrance, terminal = T4).toArrival(LiveFeedSource), Set())
  val redListT5preT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = beforeT4Opening, origin = redListOriginInBolivia, terminal = T5).toArrival(LiveFeedSource), Set())
  val nonRedListT5preT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = beforeT4Opening, origin = nonRedListOriginInFrance, terminal = T5).toArrival(LiveFeedSource), Set())

  val redListT2postT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = afterT4Opening, origin = redListOriginInBolivia, terminal = T2).toArrival(LiveFeedSource), Set())
  val nonRedListT2postT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = afterT4Opening, origin = nonRedListOriginInFrance, terminal = T2).toArrival(LiveFeedSource), Set())
  val redListT3postT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = afterT4Opening, origin = redListOriginInBolivia, terminal = T3).toArrival(LiveFeedSource), Set())
  val nonRedListT3postT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = afterT4Opening, origin = nonRedListOriginInFrance, terminal = T3).toArrival(LiveFeedSource), Set())
  val redListT4postT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = afterT4Opening, origin = redListOriginInBolivia, terminal = T4).toArrival(LiveFeedSource), Set())
  val nonRedListT4postT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = afterT4Opening, origin = nonRedListOriginInFrance, terminal = T4).toArrival(LiveFeedSource), Set())
  val redListT5postT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = afterT4Opening, origin = redListOriginInBolivia, terminal = T5).toArrival(LiveFeedSource), Set())
  val nonRedListT5postT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = afterT4Opening, origin = nonRedListOriginInFrance, terminal = T5).toArrival(LiveFeedSource), Set())

  val filter = LhrFlightDisplayFilter(RedListUpdates.empty, isRedListOrigin, LhrTerminalTypes(LhrRedListDatesImpl))

  "Concerning LHR filter to include incoming red list diversions" >> {
    "Given flights for LHR spanning all terminals arriving when T3 is a red list terminal" >> {
      val flightsPreT4Opening = List(
        redListT2preT4, nonRedListT2preT4, redListT3preT4, nonRedListT3preT4, redListT4preT4, nonRedListT4preT4, redListT5preT4, nonRedListT5preT4)

      "When I ask for T2 display flights, I should get only T2 flights" >> {
        filter.forTerminalIncludingIncomingDiversions(flightsPreT4Opening, T2) === List(redListT2preT4, nonRedListT2preT4)
      }
      "When I ask for T3 display flights, I should get all the T3 flights, plus any T2 & T5 red list origin flights" >> {
        filter.forTerminalIncludingIncomingDiversions(flightsPreT4Opening, T3) === List(redListT2preT4, redListT3preT4, nonRedListT3preT4, redListT5preT4)
      }
      "When I ask for T4 display flights, I should get only T4 flights" >> {
        filter.forTerminalIncludingIncomingDiversions(flightsPreT4Opening, T4) === List(redListT4preT4, nonRedListT4preT4)
      }
      "When I ask for T5 display flights, I should get only T5 flights" >> {
        filter.forTerminalIncludingIncomingDiversions(flightsPreT4Opening, T5) === List(redListT5preT4, nonRedListT5preT4)
      }
    }

    "Given flights for LHR spanning all terminals arriving after T4 starts handling red list flights" >> {
      val flightsPostT4Opening = List(redListT2postT4, nonRedListT2postT4, redListT3postT4, nonRedListT3postT4, redListT4postT4, nonRedListT4postT4, redListT5postT4, nonRedListT5postT4)
      "When I ask for T2 display flights, I should get only T2 flights" >> {
        filter.forTerminalIncludingIncomingDiversions(flightsPostT4Opening, T2) === List(redListT2postT4, nonRedListT2postT4)
      }
      "When I ask for T3 display flights, I should get only T3 flights" >> {
        filter.forTerminalIncludingIncomingDiversions(flightsPostT4Opening, T3) === List(redListT3postT4, nonRedListT3postT4)
      }
      "When I ask for T4 display flights, I should get all the T4 flights, plus any T2, T3 & T5 red list origin flights" >> {
        filter.forTerminalIncludingIncomingDiversions(flightsPostT4Opening, T4) === List(redListT2postT4, redListT3postT4, redListT4postT4, nonRedListT4postT4, redListT5postT4)
      }
      "When I ask for T5 display flights, I should get only T5 flights" >> {
        filter.forTerminalIncludingIncomingDiversions(flightsPostT4Opening, T5) === List(redListT5postT4, nonRedListT5postT4)
      }
    }
  }

  "Concerning LHR filter to reflect red list diversions" >> {
    "Given flights for LHR spanning all terminals arriving when T3 is a red list terminal" >> {
      val flightsPreT4Opening = List(
        redListT2preT4, nonRedListT2preT4, redListT3preT4, nonRedListT3preT4, redListT4preT4, nonRedListT4preT4, redListT5preT4, nonRedListT5preT4)

      "When I ask for T2 display flights, I should get only T2 non-red list flights" >> {
        filter.forTerminalReflectingDiversions(flightsPreT4Opening, T2) === List(nonRedListT2preT4)
      }
      "When I ask for T3 display flights, I should get all the T3 flights, plus any T2 & T5 red list origin flights" >> {
        filter.forTerminalReflectingDiversions(flightsPreT4Opening, T3) === List(redListT2preT4, redListT3preT4, nonRedListT3preT4, redListT5preT4)
      }
      "When I ask for T4 display flights, I should get all T4 flights" >> {
        filter.forTerminalReflectingDiversions(flightsPreT4Opening, T4) === List(redListT4preT4, nonRedListT4preT4)
      }
      "When I ask for T5 display flights, I should get only T5 non-red list flights" >> {
        filter.forTerminalReflectingDiversions(flightsPreT4Opening, T5) === List(nonRedListT5preT4)
      }
    }

    "Given flights for LHR spanning all terminals arriving after T4 starts handling red list flights" >> {
      val flightsPostT4Opening = List(redListT2postT4, nonRedListT2postT4, redListT3postT4, nonRedListT3postT4, redListT4postT4, nonRedListT4postT4, redListT5postT4, nonRedListT5postT4)
      "When I ask for T2 display flights, I should get only T2 non-red list flights" >> {
        filter.forTerminalReflectingDiversions(flightsPostT4Opening, T2) === List(nonRedListT2postT4)
      }
      "When I ask for T3 display flights, I should get only T3 non-red list flights" >> {
        filter.forTerminalReflectingDiversions(flightsPostT4Opening, T3) === List(nonRedListT3postT4)
      }
      "When I ask for T4 display flights, I should get all the T4 flights, plus any T2, T3 & T5 red list origin flights" >> {
        filter.forTerminalReflectingDiversions(flightsPostT4Opening, T4) === List(redListT2postT4, redListT3postT4, redListT4postT4, nonRedListT4postT4, redListT5postT4)
      }
      "When I ask for T5 display flights, I should get only T5 non-red list flights" >> {
        filter.forTerminalReflectingDiversions(flightsPostT4Opening, T5) === List(nonRedListT5postT4)
      }
    }
  }
}
