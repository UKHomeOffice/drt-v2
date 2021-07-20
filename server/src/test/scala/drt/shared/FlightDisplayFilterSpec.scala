package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals._
import org.specs2.mutable.Specification

import scala.collection.immutable.HashSet

class FlightDisplayFilterSpec extends Specification {
  private val t3RedListOpeningMillis = 10L
  private val t4RedListOpeningMillis = 1000L
  private val t3NonRedListOpeningMillis = 2000L
  private val beforeT4Opening: MillisSinceEpoch = t4RedListOpeningMillis - 10
  private val afterT4Opening: MillisSinceEpoch = t4RedListOpeningMillis + 10

  val redListOriginInBolivia = PortCode("VVI")
  val nonRedListOriginInFrance = PortCode("CDG")
  val redListPorts = HashSet(redListOriginInBolivia)
  val isRedListOrigin: PortCode => Boolean = pc => redListPorts.contains(pc)

  val redListT2preT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = beforeT4Opening, origin = redListOriginInBolivia, terminal = T2), Set())
  val nonRedListT2preT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = beforeT4Opening, origin = nonRedListOriginInFrance, terminal = T2), Set())
  val redListT3preT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = beforeT4Opening, origin = redListOriginInBolivia, terminal = T3), Set())
  val nonRedListT3preT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = beforeT4Opening, origin = nonRedListOriginInFrance, terminal = T3), Set())
  val redListT4preT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = beforeT4Opening, origin = redListOriginInBolivia, terminal = T4), Set())
  val nonRedListT4preT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = beforeT4Opening, origin = nonRedListOriginInFrance, terminal = T4), Set())
  val redListT5preT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = beforeT4Opening, origin = redListOriginInBolivia, terminal = T5), Set())
  val nonRedListT5preT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = beforeT4Opening, origin = nonRedListOriginInFrance, terminal = T5), Set())

  val redListT2postT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = afterT4Opening, origin = redListOriginInBolivia, terminal = T2), Set())
  val nonRedListT2postT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = afterT4Opening, origin = nonRedListOriginInFrance, terminal = T2), Set())
  val redListT3postT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = afterT4Opening, origin = redListOriginInBolivia, terminal = T3), Set())
  val nonRedListT3postT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = afterT4Opening, origin = nonRedListOriginInFrance, terminal = T3), Set())
  val redListT4postT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = afterT4Opening, origin = redListOriginInBolivia, terminal = T4), Set())
  val nonRedListT4postT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = afterT4Opening, origin = nonRedListOriginInFrance, terminal = T4), Set())
  val redListT5postT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = afterT4Opening, origin = redListOriginInBolivia, terminal = T5), Set())
  val nonRedListT5postT4 = ApiFlightWithSplits(ArrivalGenerator.arrival(sch = afterT4Opening, origin = nonRedListOriginInFrance, terminal = T5), Set())


  val filter = LhrFlightDisplayFilter(isRedListOrigin, t3RedListOpeningMillis, t4RedListOpeningMillis, t3NonRedListOpeningMillis)

  "Given flights for LHR spanning all terminals arriving before T4 starts handling red list flights" >> {
    val flightsPreT4Opening = List(redListT2preT4, nonRedListT2preT4, redListT3preT4, nonRedListT3preT4, redListT4preT4, nonRedListT4preT4, redListT5preT4, nonRedListT5preT4)
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
    "When I ask for T4 display flights, I should get all the T4 flights, plus any T2 & T5 red list origin flights" >> {
      filter.forTerminalIncludingIncomingDiversions(flightsPostT4Opening, T4) === List(redListT2postT4, redListT4postT4, nonRedListT4postT4, redListT5postT4)
    }
    "When I ask for T5 display flights, I should get only T5 flights" >> {
      filter.forTerminalIncludingIncomingDiversions(flightsPostT4Opening, T5) === List(redListT5postT4, nonRedListT5postT4)
    }
  }
}
