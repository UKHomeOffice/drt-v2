package services.crunch

import controllers.ArrivalGenerator
import drt.server.feeds.{ArrivalsFeedSuccess, DqManifests, ManifestsFeedSuccess}
import drt.shared._
import manifests.passengers.{BestAvailableManifest, ManifestPassengerProfile}
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser
import passengersplits.parsing.VoyageManifestParser._
import services.crunch.VoyageManifestGenerator._
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.arrivals.EventTypes.{CI, DC}
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.PaxTypes._
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues._
import uk.gov.homeoffice.drt.ports.Queues._
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.SDate

import scala.collection.immutable.{List, Map, Seq, SortedMap}
import scala.concurrent.duration._


class VoyageManifestsSpec extends CrunchTestLike {
  sequential
  isolated

  val airportConfig: AirportConfig = TestDefaults.airportConfigWithEgates

  "Given 2 DQ messages for a flight, where the DC message arrives after the CI message " +
    "When I crunch the flight " +
    "Then I should see the DQ manifest was used" >> {

    val scheduled = "2017-01-01T00:00Z"

    val flight = ArrivalGenerator.live(origin = PortCode("JFK"), schDt = scheduled, iata = "TST001", terminal = T1, totalPax = Option(1))
    val inputManifestsCi = ManifestsFeedSuccess(DqManifests(0, Set(
      VoyageManifest(EventTypes.CI, PortCode("STN"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("TS"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("00:00"),
        List(
          PassengerInfoGenerator.passengerInfoJson(Nationality("GBR"), DocumentType("P"), Nationality("GBR"))
        ))
    )))
    val inputManifestsDc = ManifestsFeedSuccess(DqManifests(0, Set(
      VoyageManifest(EventTypes.DC, PortCode("STN"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("TS"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("00:00"),
        List(
          PassengerInfoGenerator.passengerInfoJson(Nationality("ZAF"), DocumentType("P"), Nationality("ZAF"))
        ))
    )))
    val crunch = runCrunchGraph(TestConfig(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        slaByQueue = Map(Queues.EGate -> 15, Queues.EeaDesk -> 25, Queues.NonEeaDesk -> 45),
        terminalProcessingTimes = Map(T1 -> Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60,
          nonVisaNationalToDesk -> 25d / 60
        )),
        queuesByTerminal = SortedMap(T1 -> Seq(EeaDesk, EGate, NonEeaDesk))
      )
    ))

    offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Seq(flight)))
    waitForFlightsInPortState(crunch.portStateTestProbe)
    offerAndWait(crunch.manifestsLiveInput, inputManifestsCi)
    Thread.sleep(1000)
    offerAndWait(crunch.manifestsLiveInput, inputManifestsDc)

    val expectedNonZeroQueues = Set(NonEeaDesk)

    crunch.portStateTestProbe.fishForMessage(3.seconds) {
      case ps: PortState =>
        val nonZeroQueues = ps.crunchMinutes.values.filter(_.paxLoad > 0).groupBy(_.queue).keys.toSet
        println(s"nonZeroQueues: $nonZeroQueues")
        nonZeroQueues == expectedNonZeroQueues
    }

    success
  }

//  "Given a VoyageManifest and its arrival where the arrival has a different number of passengers to the manifest" >> {
//    "When I crunch the flight " >> {
//      "Then I should see the passenger loads corresponding to the manifest splits applied to the arrival's passengers" >> {
//
//        val scheduled = "2017-01-01T00:00Z"
//        val portCode = PortCode("LHR")
//
//        val flight = ArrivalGenerator.forecast(origin = PortCode("JFK"), schDt = scheduled, iata = "TST001", terminal = T1, totalPax = Option(100))
//        val inputManifests = ManifestsFeedSuccess(DqManifests(0, Set(
//          VoyageManifest(EventTypes.DC, portCode, PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("00:00"),
//            manifestPax(101, euPassport))
//        )))
//        val crunch = runCrunchGraph(TestConfig(
//          now = () => SDate(scheduled),
//          airportConfig = airportConfig.copy(
//            slaByQueue = Map(Queues.EGate -> 15, Queues.EeaDesk -> 25, Queues.NonEeaDesk -> 45),
//            portCode = portCode,
//            terminalProcessingTimes = Map(T1 -> Map(
//              eeaMachineReadableToDesk -> 25d / 60,
//              eeaMachineReadableToEGate -> 25d / 60
//            )),
//            terminalPaxTypeQueueAllocation = airportConfig.terminalPaxTypeQueueAllocation.updated(
//              T1, airportConfig.terminalPaxTypeQueueAllocation(T1).updated(EeaMachineReadable, List(Queues.EGate -> 0.8, Queues.EeaDesk -> 0.2))
//            ),
//            queuesByTerminal = SortedMap(T1 -> Seq(EeaDesk, EGate))
//          )
//        ))
//
//        offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Seq(flight)))
//        waitForFlightsInPortState(crunch.portStateTestProbe)
//        offerAndWait(crunch.manifestsLiveInput, inputManifests)
//
//        val expected = Map(Queues.EeaDesk -> 4.0, Queues.EGate -> 16.0)
//
//        crunch.portStateTestProbe.fishForMessage(3.seconds) {
//          case ps: PortState =>
//            val queuePax = ps.crunchMinutes
//              .values
//              .filter(cm => cm.minute == SDate(scheduled).millisSinceEpoch)
//              .map(cm => (cm.queue, cm.paxLoad))
//              .toMap
//            queuePax == expected
//        }
//
//        success
//      }
//    }
//  }
//
//  "Given a VoyageManifest with 2 transfers and one Eea Passport " >> {
//    "When I crunch the flight with 10 pax minus 5 transit " >> {
//      "Then I should see the split applied to the non transit passengers only" >> {
//
//        val scheduled = "2017-01-01T00:00Z"
//        val portCode = PortCode("LHR")
//        val flight = ArrivalGenerator.live(origin = PortCode("JFK"), schDt = scheduled, iata = "TST001", terminal = T1, totalPax = Option(10), transPax = Option(5))
//        val inputManifests = ManifestsFeedSuccess(DqManifests(0, Set(
//          VoyageManifest(EventTypes.CI, portCode, PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("00:00"),
//            manifestPax(5, euPassport) ++ manifestPax(2, inTransitFlag) ++ manifestPax(3, inTransitCountry)
//          )
//        )))
//        val crunch = runCrunchGraph(TestConfig(
//          now = () => SDate(scheduled),
//          airportConfig = airportConfig.copy(
//            slaByQueue = Map(Queues.EGate -> 15, Queues.EeaDesk -> 25, Queues.NonEeaDesk -> 45),
//            portCode = portCode,
//            terminalProcessingTimes = Map(T1 -> Map(
//              eeaMachineReadableToDesk -> 25d / 60,
//              eeaMachineReadableToEGate -> 25d / 60
//            )),
//            terminalPaxTypeQueueAllocation = airportConfig.terminalPaxTypeQueueAllocation.updated(
//              T1, airportConfig.terminalPaxTypeQueueAllocation(T1).updated(EeaMachineReadable, List(Queues.EGate -> 0.8, Queues.EeaDesk -> 0.2),
//              )
//            ),
//            queuesByTerminal = SortedMap(T1 -> Seq(EeaDesk, EGate, NonEeaDesk)),
//            hasTransfer = true
//          )
//        ))
//
//        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Seq(flight)))
//        waitForFlightsInPortState(crunch.portStateTestProbe)
//        offerAndWait(crunch.manifestsLiveInput, inputManifests)
//
//        val expected = Map(Queues.EeaDesk -> 1.0, Queues.EGate -> 4.0, Queues.NonEeaDesk -> 0.0)
//
//        crunch.portStateTestProbe.fishForMessage(3.seconds) {
//          case ps: PortState =>
//            val queuePax = ps.crunchMinutes
//              .values
//              .filter(cm => cm.minute == SDate(scheduled).millisSinceEpoch)
//              .map(cm => (cm.queue, cm.paxLoad))
//              .toMap
//
//            queuePax == expected
//        }
//
//        success
//      }
//    }
//  }
//
//  "When converting a VoyageManifest to a BestAvailableManifest I should get passenger profiles that match the manifest" >> {
//    val vm = VoyageManifest(EventTypes.DC, PortCode("LHR"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("00:00"), List(
//      inTransitFlag,
//      inTransitCountry,
//      euPassport,
//      euIdCard,
//      visa,
//      visa
//    ))
//
//    val result = BestAvailableManifest(vm)
//
//    val expected = BestAvailableManifest(
//      ApiSplitsWithHistoricalEGateAndFTPercentages, PortCode("LHR"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), SDate("2017-01-01"),
//      List(
//        ManifestPassengerProfile(Nationality("GBR"), Option(DocumentType("P")), Option(PaxAge(22)), inTransit = true, None),
//        ManifestPassengerProfile(Nationality("GBR"), Option(DocumentType("P")), Option(PaxAge(22)), inTransit = true, None),
//        ManifestPassengerProfile(Nationality("FRA"), Option(DocumentType("P")), Option(PaxAge(22)), inTransit = false, None),
//        ManifestPassengerProfile(Nationality("ITA"), Option(DocumentType("I")), Option(PaxAge(22)), inTransit = false, None),
//        ManifestPassengerProfile(Nationality("AFG"), Option(DocumentType("P")), Option(PaxAge(22)), inTransit = false, None),
//        ManifestPassengerProfile(Nationality("AFG"), Option(DocumentType("P")), Option(PaxAge(22)), inTransit = false, None)
//      ),
//      Option(DC)
//    )
//
//    result === expected
//  }
//
//  "Given a voyage manifest `Passport` instead of `P` for doctype it should still be accepted as a passport doctype" >> {
//    val vm = VoyageManifest(EventTypes.CI, PortCode("LHR"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("00:00"), List(
//      PassengerInfoJson(Option(DocumentType("Passport")), Nationality("GBR"), EeaFlag("EEA"), Option(PaxAge(22)), Option(PortCode("LHR")), InTransit("N"), Option(Nationality("GBR")), Option(Nationality("GBR")), None)
//    ))
//
//    val result = BestAvailableManifest(vm)
//
//    val expected = BestAvailableManifest(
//      ApiSplitsWithHistoricalEGateAndFTPercentages, PortCode("LHR"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), SDate("2017-01-01"),
//      List(
//        ManifestPassengerProfile(Nationality("GBR"), Option(DocumentType.Passport), Option(PaxAge(22)), false, None)
//      ),
//      Option(CI)
//    )
//
//    result === expected
//  }
//
//  "Given a voyage manifest with a UK National and no doctype, Passport should be the implied doctype" >> {
//    val vm = VoyageManifest(EventTypes.CI, PortCode("LHR"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("00:00"), List(
//      PassengerInfoJson(None, Nationality("GBR"), EeaFlag("EEA"), Option(PaxAge(22)), Option(PortCode("LHR")), InTransit("N"), Option(Nationality("GBR")), Option(Nationality("GBR")), None)
//    ))
//
//    val result = BestAvailableManifest(vm)
//
//    val expected = BestAvailableManifest(
//      ApiSplitsWithHistoricalEGateAndFTPercentages, PortCode("LHR"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), SDate("2017-01-01"),
//      List(
//        ManifestPassengerProfile(Nationality("GBR"), Option(DocumentType.Passport), Option(PaxAge(22)), false, None)
//      ),
//      Option(CI)
//    )
//
//    result === expected
//  }
//
//  "Given a VoyageManifest with 2 transfers, 1 Eea Passport, 1 Eea Id card, and 2 visa nationals " >> {
//    "When I crunch the flight with 4 non-transit pax (10 pax minus 6 transit) " >> {
//      "Then I should see the 4 non-transit pax go to egates (1), eea desk (1), and non-eea (2)" >> {
//
//        val scheduled = "2017-01-01T00:00Z"
//        val portCode = PortCode("LHR")
//
//        val flight = ArrivalGenerator.live(origin = PortCode("JFK"), schDt = scheduled, iata = "TST001", terminal = T1, totalPax = Option(10), transPax = Option(6))
//        val inputManifests = ManifestsFeedSuccess(DqManifests(0, Set(
//          VoyageManifest(EventTypes.CI, portCode, PortCode("JFK"), VoyageNumber("0001"), CarrierCode("TS"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("00:00"), List(
//            inTransitFlag,
//            inTransitCountry,
//            euPassport,
//            euIdCard,
//            visa,
//            visa
//          ))
//        )))
//        val crunch = runCrunchGraph(TestConfig(
//          now = () => SDate(scheduled),
//          airportConfig = airportConfig.copy(
//            slaByQueue = Map(Queues.EGate -> 15, Queues.EeaDesk -> 25, Queues.NonEeaDesk -> 45),
//            portCode = portCode,
//            terminalProcessingTimes = Map(T1 -> Map(
//              eeaMachineReadableToDesk -> 25d / 60,
//              eeaNonMachineReadableToDesk -> 25d / 60,
//              eeaMachineReadableToEGate -> 25d / 60,
//              visaNationalToDesk -> 25d / 60
//            )),
//            terminalPaxTypeQueueAllocation = airportConfig.terminalPaxTypeQueueAllocation.updated(
//              T1, airportConfig.terminalPaxTypeQueueAllocation(T1).updated(EeaMachineReadable, List(Queues.EGate -> 0.8, Queues.EeaDesk -> 0.2),
//              )
//            ),
//            queuesByTerminal = SortedMap(T1 -> Seq(EeaDesk, EGate, NonEeaDesk)),
//            hasTransfer = true
//          )
//        ))
//
//        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Seq(flight)))
//        waitForFlightsInPortState(crunch.portStateTestProbe)
//        offerAndWait(crunch.manifestsLiveInput, inputManifests)
//
//        val expected = Map(Queues.EeaDesk -> 1, Queues.EGate -> 1, Queues.NonEeaDesk -> 2.0)
//
//        crunch.portStateTestProbe.fishForMessage(2.seconds) {
//          case ps: PortState =>
//            val queuePax = ps.crunchMinutes
//              .values
//              .filter(cm => cm.minute == SDate(scheduled).millisSinceEpoch)
//              .map(cm => (cm.queue, cm.paxLoad))
//              .toMap
//
//            queuePax == expected
//        }
//
//        success
//      }
//    }
//  }
//
//  "Given a VoyageManifest with multiple records for each passenger with unique passenger identifiers " +
//    "When using API data for passenger numbers " +
//    "I should get the sum of the unique identifiers, not the total number of passenger info records" >> {
//
//    val scheduled = "2017-01-01T00:00Z"
//    val portCode = PortCode("LHR")
//
//    val flight = ArrivalGenerator.forecast(origin = PortCode("JFK"), schDt = scheduled, iata = "TST001", terminal = T1)
//    val inputManifests = ManifestsFeedSuccess(DqManifests(0, Set(
//      VoyageManifest(EventTypes.CI, portCode, PortCode("JFK"), VoyageNumber(1), CarrierCode("TS"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("00:00"), List(
//        euPassportWithIdentifier("ID1"),
//        euPassportWithIdentifier("ID1"),
//        euPassportWithIdentifier("ID2")
//      ))
//    )))
//    val crunch = runCrunchGraph(TestConfig(
//      now = () => SDate(scheduled),
//      airportConfig = airportConfig.copy(
//        slaByQueue = Map(Queues.EGate -> 15, Queues.EeaDesk -> 25, Queues.NonEeaDesk -> 45),
//        portCode = portCode,
//        terminalProcessingTimes = Map(T1 -> Map(
//          eeaMachineReadableToDesk -> 25d / 60,
//          eeaNonMachineReadableToDesk -> 25d / 60,
//          eeaMachineReadableToEGate -> 25d / 60,
//          visaNationalToDesk -> 25d / 60
//        )),
//        queuesByTerminal = SortedMap(T1 -> Seq(EeaDesk, EGate, NonEeaDesk))
//      )
//    ))
//
//    offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Seq(flight)))
//    waitForFlightsInPortState(crunch.portStateTestProbe)
//    offerAndWait(crunch.manifestsLiveInput, inputManifests)
//
//    val expected = 2
//
//    crunch.portStateTestProbe.fishForMessage(1.seconds) {
//      case ps: PortState =>
//        val queuePax = paxLoadsFromPortState(ps, 60, 0)
//          .values
//          .flatMap(_.values)
//          .flatten
//          .sum
//
//        Math.round(queuePax) == expected
//    }
//
//    success
//  }
//
//  "Given a VoyageManifest with multiple passenger records and no Passenger Identifiers " +
//    "When using API to calculate passenger numbers each passenger info record should be counted" >> {
//
//    val scheduled = "2017-01-01T00:00Z"
//    val portCode = PortCode("LHR")
//
//    val flight = ArrivalGenerator.forecast(origin = PortCode("JFK"), schDt = scheduled, iata = "TST001", terminal = T1, totalPax = Option(6))
//    val inputManifests = ManifestsFeedSuccess(DqManifests(0, Set(
//      VoyageManifest(EventTypes.CI, portCode, PortCode("JFK"), VoyageNumber(1), CarrierCode("TS"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("00:00"), List(
//        euPassport,
//        euPassport,
//        euPassport,
//      ))
//    )))
//    val crunch = runCrunchGraph(TestConfig(
//      now = () => SDate(scheduled),
//      airportConfig = airportConfig.copy(
//        slaByQueue = Map(Queues.EGate -> 15, Queues.EeaDesk -> 25, Queues.NonEeaDesk -> 45),
//        portCode = portCode,
//        terminalProcessingTimes = Map(T1 -> Map(
//          eeaMachineReadableToDesk -> 25d / 60,
//          eeaNonMachineReadableToDesk -> 25d / 60,
//          eeaMachineReadableToEGate -> 25d / 60,
//          visaNationalToDesk -> 25d / 60
//        )),
//        queuesByTerminal = SortedMap(T1 -> Seq(EeaDesk, EGate, NonEeaDesk))
//      )
//    ))
//
//    offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Seq(flight)))
//    waitForFlightsInPortState(crunch.portStateTestProbe)
//    offerAndWait(crunch.manifestsLiveInput, inputManifests)
//
//    val expected = 3
//
//    crunch.portStateTestProbe.fishForMessage(1.seconds) {
//      case ps: PortState =>
//        val queuePax = paxLoadsFromPortState(ps, 60, 0)
//          .values
//          .flatMap((_.values))
//          .flatten
//          .sum
//
//        Math.round(queuePax) == expected
//    }
//
//    success
//  }
//
//  "Given a VoyageManifest containing some records with a passenger identifier, we should ignore all records without one." >> {
//
//    val scheduled = "2017-01-01T00:00Z"
//    val portCode = PortCode("LHR")
//
//    val flight = ArrivalGenerator.forecast(origin = PortCode("JFK"), schDt = scheduled, iata = "TST001", terminal = T1, transPax = Option(6))
//    val inputManifests = ManifestsFeedSuccess(DqManifests(0, Set(
//      VoyageManifest(EventTypes.CI, portCode, PortCode("JFK"), VoyageNumber(1), CarrierCode("TS"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("00:00"), List(
//        euPassportWithIdentifier("Id1"),
//        euPassportWithIdentifier("Id2"),
//        euPassport,
//        euPassport,
//      ))
//    )))
//    val crunch = runCrunchGraph(TestConfig(
//      now = () => SDate(scheduled),
//      airportConfig = airportConfig.copy(
//        slaByQueue = Map(Queues.EGate -> 15, Queues.EeaDesk -> 25, Queues.NonEeaDesk -> 45),
//        portCode = portCode,
//        terminalProcessingTimes = Map(T1 -> Map(
//          eeaMachineReadableToDesk -> 25d / 60,
//          eeaNonMachineReadableToDesk -> 25d / 60,
//          eeaMachineReadableToEGate -> 25d / 60,
//          visaNationalToDesk -> 25d / 60
//        )),
//        queuesByTerminal = SortedMap(T1 -> Seq(EeaDesk, EGate, NonEeaDesk))
//      )
//    ))
//
//    offerAndWait(crunch.aclArrivalsInput, ArrivalsFeedSuccess(Seq(flight)))
//    waitForFlightsInPortState(crunch.portStateTestProbe)
//    offerAndWait(crunch.manifestsLiveInput, inputManifests)
//
//    val expected = 2
//
//    crunch.portStateTestProbe.fishForMessage(1.seconds) {
//      case ps: PortState =>
//        val queuePax = paxLoadsFromPortState(ps, 60, 0)
//          .values
//          .flatMap((_.values))
//          .flatten
//          .sum
//
//        Math.round(queuePax) == expected
//    }
//
//    success
//  }
//
//  "Given a voyage manifest json string I should get back a correctly parsed VoyageManifest" >> {
//    val manifestString =
//      """|{
//         |    "EventCode": "DC",
//         |    "DeparturePortCode": "AMS",
//         |    "VoyageNumberTrailingLetter": "",
//         |    "ArrivalPortCode": "TST",
//         |    "DeparturePortCountryCode": "MAR",
//         |    "VoyageNumber": "0123",
//         |    "VoyageKey": "key",
//         |    "ScheduledDateOfDeparture": "2020-09-07",
//         |    "ScheduledDateOfArrival": "2020-09-07",
//         |    "CarrierType": "AIR",
//         |    "CarrierCode": "TS",
//         |    "ScheduledTimeOfDeparture": "06:30:00",
//         |    "ScheduledTimeOfArrival": "09:30:00",
//         |    "FileId": "fileID",
//         |    "PassengerList": [
//         |        {
//         |            "DocumentIssuingCountryCode": "GBR",
//         |            "PersonType": "P",
//         |            "DocumentLevel": "Primary",
//         |            "Age": "30",
//         |            "DisembarkationPortCode": "TST",
//         |            "InTransitFlag": "N",
//         |            "DisembarkationPortCountryCode": "TST",
//         |            "NationalityCountryEEAFlag": "EEA",
//         |            "PassengerIdentifier": "id",
//         |            "DocumentType": "P",
//         |            "PoavKey": "1",
//         |            "NationalityCountryCode": "GBR"
//         |         }
//         |    ]
//         |}""".stripMargin
//
//    val result = VoyageManifestParser.parseVoyagePassengerInfo(manifestString).get
//
//    val expected = VoyageManifest(
//      EventCode = EventType("DC"),
//      ArrivalPortCode = PortCode("TST"),
//      DeparturePortCode = PortCode("AMS"),
//      VoyageNumber = VoyageNumber(123),
//      CarrierCode = CarrierCode("TS"),
//      ScheduledDateOfArrival = ManifestDateOfArrival("2020-09-07"),
//      ScheduledTimeOfArrival = ManifestTimeOfArrival("09:30:00"),
//      PassengerList = List(PassengerInfoJson(
//        DocumentType = Option(DocumentType("P")),
//        DocumentIssuingCountryCode = Nationality("GBR"),
//        EEAFlag = EeaFlag("EEA"),
//        Age = Option(PaxAge(30)),
//        DisembarkationPortCode = Option(PortCode("TST")),
//        InTransitFlag = InTransit("N"),
//        DisembarkationPortCountryCode = Some(Nationality("TST")),
//        NationalityCountryCode = Some(Nationality("GBR")),
//        PassengerIdentifier = Option("id")
//      ))
//    )
//
//    result === expected
//  }
}

object PassengerInfoGenerator {
  def passengerInfoJson(nationality: Nationality, documentType: DocumentType, issuingCountry: Nationality): PassengerInfoJson = {
    PassengerInfoJson(Option(documentType), issuingCountry, EeaFlag(""), Option(PaxAge(22)), Option(PortCode("LHR")), InTransit("N"), Option(Nationality("GBR")), Option(nationality), None)
  }
}
