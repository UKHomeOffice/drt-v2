package services.graphstages

import actors.ArrivalGenerator
import drt.shared.Terminals.{T1, T2, T3, T4, T5, Terminal}
import drt.shared.airportconfig.Lhr
import drt.shared.{ApiFlightWithSplits, PortCode}
import org.specs2.mutable.Specification
import services.graphstages.FlightFilter.ValidTerminalFilter

class FlightFilterSpec extends Specification {
  "LHR red list filter" >> {
    "Given a flight from Bulawayo coming to LHR T2, the filter should return false to filter it out" >> {
      FlightFilter.lhrRedListFilter.apply(fws(PortCode("BUQ"), T2)) === false
    }
    "Given a flight from Bulawayo coming to LHR T3, the filter should return true to keep it" >> {
      FlightFilter.lhrRedListFilter.apply(fws(PortCode("BUQ"), T3)) === true
    }
    "Given a flight from Bulawayo coming to LHR T4, the filter should return true to keep it" >> {
      FlightFilter.lhrRedListFilter.apply(fws(PortCode("BUQ"), T4)) === true
    }
    "Given a flight from Bulawayo coming to LHR T5, the filter should return false to filter it out" >> {
      FlightFilter.lhrRedListFilter.apply(fws(PortCode("BUQ"), T5)) === false
    }

    "Given LHR's config & a flight from Bulawayo coming to LHR T4, the filter should return true to keep it" >> {
      FlightFilter.forPortConfig(Lhr.config).apply(fws(PortCode("BUQ"), T4)) === true
    }
    "Given LHR's config & a flight from Bulawayo coming to LHR T5, the filter should return false to filter it out" >> {
      FlightFilter.forPortConfig(Lhr.config).apply(fws(PortCode("BUQ"), T5)) === false
    }
  }
  "Not cancelled filter" >> {
    "Given a flight with a scheduled status, the filter should return true to keep it" >> {
      val fws = ApiFlightWithSplits(ArrivalGenerator.arrival(status = "scheduled"), Set())
      FlightFilter.notCancelledFilter.apply(fws) === true
    }
    "Given a flight with a cancelled status, the filter should return false to keep it" >> {
      val fws = ApiFlightWithSplits(ArrivalGenerator.arrival(status = "cancelled"), Set())
      FlightFilter.notCancelledFilter.apply(fws) === false
    }
  }
  "Outside CTA filter" >> {
    "Given a flight from JFK (outside the CTA), the filter should return true to keep it" >> {
      val fws = ApiFlightWithSplits(ArrivalGenerator.arrival(origin = PortCode("JFK")), Set())
      FlightFilter.outsideCtaFilter.apply(fws) === true
    }
    "Given a flight from DUB (inside the CTA), the filter should return false to keep it" >> {
      val fws = ApiFlightWithSplits(ArrivalGenerator.arrival(origin = PortCode("DUB")), Set())
      FlightFilter.outsideCtaFilter.apply(fws) === false
    }
  }
  "Valid terminal filter" >> {
    "Given a flight to T1 when the port only has T1, the filter should return true to keep it" >> {
      val fws = ApiFlightWithSplits(ArrivalGenerator.arrival(terminal = T1), Set())
      ValidTerminalFilter(List(T1)).apply(fws) === true
    }
    "Given a flight to T2 when the port only has T1, the filter should return true to keep it" >> {
      val fws = ApiFlightWithSplits(ArrivalGenerator.arrival(terminal = T2), Set())
      ValidTerminalFilter(List(T1)).apply(fws) === false
    }
  }

  private def fws(origin: PortCode, terminal: Terminal): ApiFlightWithSplits = {
    ApiFlightWithSplits(ArrivalGenerator.arrival(actPax = Option(10), origin = origin, terminal = terminal), Set())
  }
}
