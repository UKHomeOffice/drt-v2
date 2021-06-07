package services.exports

import controllers.ArrivalGenerator
import drt.shared.PaxTypes.{EeaMachineReadable, UndefinedPaxType}
import drt.shared.Queues.EeaDesk
import drt.shared.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import drt.shared.{ApiFlightWithSplits, ApiPaxTypeAndQueueCount, PaxNumbers, Splits}
import org.specs2.mutable.Specification
import services.exports.Exports.actualAPISplitsAndHeadingsFromFlight

class ExportsSpec extends Specification {
  "Given a flight with one undefined pax type and one eea-mr pax type in the splits" >> {
    "When I ask for the API splits and headings" >> {
      "I should see both pax types" >> {
        val arrival = ArrivalGenerator.arrival("BA0001", actPax = Option(100))
        val undefined = ApiPaxTypeAndQueueCount(UndefinedPaxType, EeaDesk, 1.0, None, None)
        val eeaMr = ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1.0, None, None)
        val fws = ApiFlightWithSplits(arrival, Set(Splits(Set(undefined, eeaMr), ApiSplitsWithHistoricalEGateAndFTPercentages, None, PaxNumbers)))
        val result = actualAPISplitsAndHeadingsFromFlight(fws)

        result === Set(("API Actual - Undefined to EEA", 1.0), ("API Actual - EEA Machine Readable to EEA", 1.0))
      }
    }
  }
}
