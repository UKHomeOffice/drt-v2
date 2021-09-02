package services.graphstages

import controllers.ArrivalGenerator
import drt.shared.Terminals._
import drt.shared.airportconfig.Lhr
import drt.shared.redlist.RedListUpdates
import drt.shared.{ApiFlightWithSplits, ArrivalStatus, PortCode}
import org.specs2.mutable.Specification

class FlightFilterSpec extends Specification {
  "LHR red list filter" >> {
    "Given a flight from Bulawayo coming to LHR T2, the filter should return false to filter it out" >> {
      FlightFilter.lhrRedListFilter.apply(fws(PortCode("BUQ"), T2), RedListUpdates.empty) === false
    }
    "Given a flight from Bulawayo coming to LHR T3, the filter should return true to keep it" >> {
      FlightFilter.lhrRedListFilter.apply(fws(PortCode("BUQ"), T3), RedListUpdates.empty) === true
    }
    "Given a flight from Bulawayo coming to LHR T4, the filter should return true to keep it" >> {
      FlightFilter.lhrRedListFilter.apply(fws(PortCode("BUQ"), T4), RedListUpdates.empty) === true
    }
    "Given a flight from Bulawayo coming to LHR T5, the filter should return false to filter it out" >> {
      FlightFilter.lhrRedListFilter.apply(fws(PortCode("BUQ"), T5), RedListUpdates.empty) === false
    }

    "Given LHR's config & a flight from Bulawayo coming to LHR T4, the filter should return true to keep it" >> {
      FlightFilter.forPortConfig(Lhr.config).apply(fws(PortCode("BUQ"), T4), RedListUpdates.empty) === true
    }
    "Given LHR's config & a flight from Bulawayo coming to LHR T5, the filter should return false to filter it out" >> {
      FlightFilter.forPortConfig(Lhr.config).apply(fws(PortCode("BUQ"), T5), RedListUpdates.empty) === false
    }
  }
  "Not cancelled filter" >> {
    "Given a flight with a scheduled status, the filter should return true to keep it" >> {
      val fws = ApiFlightWithSplits(ArrivalGenerator.arrival(status = ArrivalStatus("scheduled")), Set())
      FlightFilter.notCancelledFilter.apply(fws, RedListUpdates.empty) === true
    }
    "Given a flight with a cancelled status, the filter should return false to keep it" >> {
      val fws = ApiFlightWithSplits(ArrivalGenerator.arrival(status = ArrivalStatus("cancelled")), Set())
      FlightFilter.notCancelledFilter.apply(fws, RedListUpdates.empty) === false
    }
  }
  "Outside CTA filter" >> {
    "Given a flight from JFK (outside the CTA), the filter should return true to keep it" >> {
      val fws = ApiFlightWithSplits(ArrivalGenerator.arrival(origin = PortCode("JFK")), Set())
      FlightFilter.outsideCtaFilter.apply(fws, RedListUpdates.empty) === true
    }
    "Given a flight from DUB (inside the CTA), the filter should return false to keep it" >> {
      val fws = ApiFlightWithSplits(ArrivalGenerator.arrival(origin = PortCode("DUB")), Set())
      FlightFilter.outsideCtaFilter.apply(fws, RedListUpdates.empty) === false
    }
  }
  "Valid terminal filter" >> {
    "Given a flight to T1 when the port only has T1, the filter should return true to keep it" >> {
      val fws = ApiFlightWithSplits(ArrivalGenerator.arrival(terminal = T1), Set())
      FlightFilter.validTerminalFilter(List(T1)).apply(fws, RedListUpdates.empty) === true
    }
    "Given a flight to T2 when the port only has T1, the filter should return true to keep it" >> {
      val fws = ApiFlightWithSplits(ArrivalGenerator.arrival(terminal = T2), Set())
      FlightFilter.validTerminalFilter(List(T1)).apply(fws, RedListUpdates.empty) === false
    }
  }

  private def fws(origin: PortCode, terminal: Terminal): ApiFlightWithSplits = {
    ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = "2021-06-01T12:00", actPax = Option(10), origin = origin, terminal = terminal), Set())
  }
}
