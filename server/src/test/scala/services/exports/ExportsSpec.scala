package services.exports

import controllers.ArrivalGenerator
import drt.shared.{ApiFlightWithSplits, PaxNumbers, Splits}
import org.specs2.mutable.Specification
import services.exports.Exports.actualAPISplitsAndHeadingsFromFlight
import uk.gov.homeoffice.drt.ports.ApiPaxTypeAndQueueCount
import uk.gov.homeoffice.drt.ports.PaxTypes.{EeaMachineReadable, UndefinedPaxType}
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages

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
