package passengersplits

import drt.shared.PassengerQueueTypes.PaxTypeAndQueueCounts
import drt.shared.PassengerSplits.SplitsPaxTypeAndQueueCount
import drt.shared.Queues
import org.specs2._
import org.specs2.specification.script.StandardDelimitedStepParsers
import passengersplits.core.PassengerQueueCalculator
import passengersplits.parsing.VoyageManifestParser.{EventCodes, PassengerInfo, PassengerInfoJson, VoyageManifest}

class PaxTransferSpecs extends Specification with specification.dsl.GWT with StandardDelimitedStepParsers {
  def is =
    s2"""
    This is a specification of passenger splits with transfer passengers from the DQ Advance Passenger Info (API) feed

        |As an LHR SO user of DRT I would like to see the transfer passengers that are arriving for a flight.
        |The transfer passengers should be removed from the API pax numbers arriving at the PCP and displayed separately in a Transfer column.
        |This will ensure that I know the number of passengers that should be seen at PCP from a flight,
        |it will also highlight the number of additional passengers that could be seen by the
        |PCP if the arrival of the flight is delayed and the passengers miss their connecting flight.
        |
        |opt - It would be useful to see the number of international and domestic transfers - hover over could be used?
        |
        |TransferQueue
        | - $transferPaxGoToATransferQueue
        |
        |
    """

  def transferPaxGoToATransferQueue =
    s2"""
    Given A Flight $createFlight
     And the flight has passengers
     And a Passenger is from {UK} {InTransit} disembarking {BCN} $addPassenger
     And a Passenger is from {DEU} {NotInTransit} disembarking {LHR} $addPassenger
    When we calculate the splits $calcSplits
    Then we do NOT see them in the split counts
    SplitCounts are eeaDesk {1} transfers {1} $assertSplits
    """

  var currentFlight: Option[VoyageManifest] = None
  var calculatedSplits: List[SplitsPaxTypeAndQueueCount] = Nil

  def splitsByQueue = calculatedSplits.groupBy(_.queueType).mapValues(v => v.map(_.paxCount).sum)

  def createFlight = step {
    println(s"create a flight")
    currentFlight = Some(VoyageManifest(EventCodes.CheckIn, "LHR", "MON", "123", "RYR", "2017-05-02", "10:33:00", Nil))
  }

  def addPassenger = step(threeStrings) {
    case (countryCode: String, transferState: String, disembarkation: String) => {
      println(s"passenger is $countryCode $transferState")

      val inTransit = transferState match {
        case "InTransit" => "Y"
        case "NotInTransit" => "N"
      }

      val newPassenger = PassengerInfoJson(Some("P"), countryCode, EEAFlag = "EEA", None, Some(disembarkation), inTransit)
      currentFlight = currentFlight.map(f => f.copy(PassengerList = newPassenger :: f.PassengerList))
      println(s"flight is now $currentFlight")
    }
  }

  def calcSplits = step {
    for (flight <- currentFlight) {
      calculatedSplits = PassengerQueueCalculator.convertPassengerInfoToPaxQueueCounts(flight.PassengerList, 0).toList
    }
  }

  def assertSplits = example(twoInts) {
    case (eea: Int, transfer: Int) => {
      println(s"expected splits are $eea $transfer")
      println(s"actualSplits are $splitsByQueue")
      (Map(Queues.EeaDesk -> eea, Queues.Transfer -> transfer) must_== splitsByQueue)
    }
  }

  def transferPaxCanBeRetrievedIndependently = pending("tbd")
}
