package passengersplits

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.SpecificationLike
import org.specs2.specification.AfterAll
import passengersplits.core.PassengerInfoRouterActor.{PassengerSplitsAck, ReportVoyagePaxSplit}
import passengersplits.core.{Core, CoreActors}
import passengersplits.parsing.VoyageManifestParser.{EventCodes, PassengerInfoJson, VoyageManifest}
import services.SDate
import services.SDate.implicits._
import drt.shared.PassengerSplits.{FlightNotFound, SplitsPaxTypeAndQueueCount, VoyagePaxSplits}
import drt.shared.PaxTypes._
import drt.shared.Queues._
import drt.shared.{Arrival, PassengerQueueTypes, SDateLike}


class CanFindASplitForAnApiFlightSpec extends
  TestKit(ActorSystem("CanFindASplitForAnApiFlightSpec", ConfigFactory.empty())) with AfterAll with SpecificationLike with ImplicitSender with CoreActors with Core {
  test =>

  isolated
  ignoreMsg {
    case PassengerSplitsAck => true
  }


  "Can parse an IATA to carrier code and voyage number" >> {
    import drt.shared.FlightParsing._
    parseIataToCarrierCodeVoyageNumber("FR8364") === Some(("FR", "8364"))
    parseIataToCarrierCodeVoyageNumber("FR836") === Some(("FR", "836"))
    parseIataToCarrierCodeVoyageNumber("FR836F") === Some(("FR", "836"))
    parseIataToCarrierCodeVoyageNumber("U2836F") === Some(("U2", "836"))
    parseIataToCarrierCodeVoyageNumber("0B836F") === Some(("0B", "836"))
  }

  "Can parse an ICAO to carrier code and voyage number" >> {
    import drt.shared.FlightParsing._
    parseIataToCarrierCodeVoyageNumber("RYR8364") === Some(("RYR", "8364"))
    parseIataToCarrierCodeVoyageNumber("RYR836") === Some(("RYR", "836"))
    parseIataToCarrierCodeVoyageNumber("RYR836F") === Some(("RYR", "836"))
  }

  "Should be able to find a flight" >> {
    def paxInfo(documentType: Some[String] = Some("P"),
                documentIssuingCountryCode: String = "GBR", eeaFlag: String = "EEA", age: Option[String] = None): PassengerInfoJson =
      PassengerInfoJson(documentType, documentIssuingCountryCode, eeaFlag, age, None,
        DisembarkationPortCountryCode =  None)
    "Given a single flight, with just one GBR passenger" in {
      flightPassengerReporter ! VoyageManifest(EventCodes.DoorsClosed, "LGW", "BRG", "12345", "EZ", "2017-04-02", "15:33:00",
        paxInfo() :: Nil)

      "When we ask for a report of voyage pax splits then we should see pax splits of the 1 passenger in eeaDesk queue" in {
        val flightScheduledDateTime = SDate(2017, 4, 2, 15, 33)
        flightPassengerReporter ! ReportVoyagePaxSplit("LGW", "EZ", "12345", flightScheduledDateTime)
        val expectedPaxSplits = List(SplitsPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1))
        expectMsg(VoyagePaxSplits("LGW", "EZ", "12345", 1, flightScheduledDateTime, expectedPaxSplits))
        success
      }
    }

    "Given a single flight STN EZ789 flight, with just one GBR and one nationals passenger" in {
      "When we ask for a report of voyage pax splits" in {
        flightPassengerReporter ! VoyageManifest(EventCodes.DoorsClosed, "STN", "BRG", "789", "EZ", "2015-02-01", "13:55:00",
          paxInfo() ::
            PassengerInfoJson(Some("P"), "NZL", "", None, DisembarkationPortCode = Some("STN")) ::
            Nil)

        val scheduleArrivalTime = SDate(2015, 2, 1, 13, 55)
        flightPassengerReporter ! ReportVoyagePaxSplit("STN", "EZ", "789", scheduleArrivalTime)

        val expectedPaxSplits = List(
          SplitsPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1),
          SplitsPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 1)
        )
        expectMsg(VoyagePaxSplits("STN", "EZ", "789", 2, scheduleArrivalTime, expectedPaxSplits))
        success
      }
    }
    "Given a single flight STN EZ789 flight where the scheduled date is in British Summer Time (BST) with just one GBR and one nationals passenger" in {
      "When we ask for a report of voyage pax splits" in {
        flightPassengerReporter ! VoyageManifest(EventCodes.DoorsClosed, "STN", "BRG", "789", "EZ", "2017-03-27", "12:10:00",
          paxInfo() ::
            PassengerInfoJson(Some("P"), "NZL", "", None, DisembarkationPortCode = Some("STN")) ::
            Nil)

        val scheduleArrivalTime = SDate(2017, 3,27, 12, 10)
        flightPassengerReporter ! ReportVoyagePaxSplit("STN", "EZ", "789", scheduleArrivalTime)

        val expectedPaxSplits = List(
          SplitsPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1),
          SplitsPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 1)
        )
        expectMsg(VoyagePaxSplits("STN", "EZ", "789", 2, scheduleArrivalTime, expectedPaxSplits))
        success
      }
    }
    "Given a single flight STN BA978 flight, with 100 passengers, and a default egate usage of 60% - " in  {
      "When we ask for a report of voyage pax splits" in {
        flightPassengerReporter ! VoyageManifest(EventCodes.DoorsClosed, "STN", "BCN", "978", "BA", "2015-07-12", "10:22:00",
          List.tabulate(80)(passengerNumber => PassengerInfoJson(Some("P"), "GBR", "EEA", Some((passengerNumber % 60 + 16).toString), DisembarkationPortCode = Some("STN"))) :::
            List.tabulate(20)(_ => PassengerInfoJson(Some("P"), "NZL", "", None, DisembarkationPortCode = Some("STN"))))

        val scheduleArrivalSDate: SDateLike = SDate(2015, 7, 12, 10, 22)
        flightPassengerReporter ! ReportVoyagePaxSplit("STN", "BA", "978", scheduleArrivalSDate)
        expectMsg(VoyagePaxSplits("STN", "BA", "978", 100, scheduleArrivalSDate, List(
          SplitsPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 32),
          SplitsPaxTypeAndQueueCount(EeaMachineReadable, EGate, 48),
          SplitsPaxTypeAndQueueCount(NonVisaNational, NonEeaDesk, 20)
        )))
        success
      }.pendingUntilFixed("ignore - possibly delete - we have disabled AdvPaxInfo egate")
    }

    "Given no flights" in {
      "When we ask for a report of voyage pax splits of a flight we don't know about then we get FlightNotFound " in {
        flightPassengerReporter ! ReportVoyagePaxSplit("NON", "DNE", "999", SDate(2015, 6, 1, 13, 55))
        expectMsg(FlightNotFound("DNE", "999", SDate(2015, 6, 1, 13, 55)))
        success
      }
    }
  }

  def afterAll() = system.terminate()
}




