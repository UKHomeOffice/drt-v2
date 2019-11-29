package services.crunch

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.{CrunchMinute, StaffMinute}
import drt.shared.PaxTypesAndQueues._
import drt.shared.Queues._
import drt.shared.SplitRatiosNs.SplitSources._
import drt.shared.Terminals.T1
import drt.shared._
import manifests.passengers.{BestAvailableManifest, ManifestPassengerProfile}
import passengersplits.core.PassengerTypeCalculatorValues.DocumentType
import passengersplits.parsing.VoyageManifestParser.{ManifestDateOfArrival, ManifestTimeOfArrival, PassengerInfoJson, VoyageManifest}
import server.feeds.{DqManifests, ManifestsFeedSuccess}
import services.SDate
import services.crunch.VoyageManifestGenerator._

import scala.collection.immutable.{Seq, SortedMap}
import scala.concurrent.duration._


class VoyageManifestsSpec extends CrunchTestLike {
  sequential
  isolated

  "Given 2 DQ messages for a flight, where the DC message arrives after the CI message " +
    "When I crunch the flight " +
    "Then I should see the DQ manifest was used" >> {

    val scheduled = "2017-01-01T00:00Z"

    val flight = ArrivalGenerator.arrival(origin = PortCode("JFK"), schDt = scheduled, iata = "TST001", terminal = T1, actPax = Option(21))
    val inputManifestsCi = ManifestsFeedSuccess(DqManifests("", Set(
      VoyageManifest(EventTypes.CI, PortCode("STN"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("TS"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("00:00"), List(
        PassengerInfoGenerator.passengerInfoJson(Nationality("GBR"), DocumentType("P"), Nationality("GBR"))
      ))
    )))
    val inputManifestsDc = ManifestsFeedSuccess(DqManifests("", Set(
      VoyageManifest(EventTypes.DC, PortCode("STN"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("TS"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("00:00"), List(
        PassengerInfoGenerator.passengerInfoJson(Nationality("ZAF"), DocumentType("P"), Nationality("ZAF"))
      ))
    )))
    val crunch: CrunchGraphInputsAndProbes = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        terminalProcessingTimes = Map(T1 -> Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60,
          nonVisaNationalToDesk -> 25d / 60
        )),
        terminals = Seq(T1),
        queues = Map(T1 -> Seq(EeaDesk, EGate, NonEeaDesk))
      ),
      initialPortState = Option(PortState(SortedMap(flight.unique -> ApiFlightWithSplits(flight, Set())), SortedMap[TQM, CrunchMinute](), SortedMap[TM, StaffMinute]()))
    )

    offerAndWait(crunch.manifestsLiveInput, inputManifestsCi)
    Thread.sleep(1500)
    offerAndWait(crunch.manifestsLiveInput, inputManifestsDc)

    val expectedNonZeroQueues = Set(NonEeaDesk)

    crunch.portStateTestProbe.fishForMessage(3 seconds) {
      case ps: PortState =>
        val nonZeroQueues = ps.crunchMinutes.values.filter(_.paxLoad > 0).groupBy(_.queue).keys.toSet
        nonZeroQueues == expectedNonZeroQueues
    }

    crunch.liveArrivalsInput.complete()

    success
  }

  "Given a VoyageManifest and its arrival where the arrival has a different number of passengers to the manifest " +
    "When I crunch the flight " +
    "Then I should see the passenger loads corresponding to the manifest splits applied to the arrival's passengers" >> {

    val scheduled = "2017-01-01T00:00Z"
    val portCode = PortCode("LHR")

    val flight = ArrivalGenerator.arrival(origin = PortCode("JFK"), schDt = scheduled, iata = "TST001", terminal = T1, actPax = Option(10))
    val inputManifests = ManifestsFeedSuccess(DqManifests("", Set(
      VoyageManifest(EventTypes.CI, portCode, PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("00:00"), manifestPax(10, euPassport))
    )))
    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        portCode = portCode,
        terminalProcessingTimes = Map(T1 -> Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60
        )),
        terminals = Seq(T1),
        queues = Map(T1 -> Seq(EeaDesk, EGate))
      ),
      initialPortState = Option(PortState(SortedMap(flight.unique -> ApiFlightWithSplits(flight, Set())), SortedMap[TQM, CrunchMinute](), SortedMap[TM, StaffMinute]()))
    )

    offerAndWait(crunch.manifestsLiveInput, inputManifests)

    val expected = Map(Queues.EeaDesk -> 2.0, Queues.EGate -> 8.0)

    crunch.portStateTestProbe.fishForMessage(3 seconds) {
      case ps: PortState =>
        val queuePax = ps.crunchMinutes
          .values
          .filter(cm => cm.minute == SDate(scheduled).millisSinceEpoch)
          .map(cm => (cm.queue, cm.paxLoad))
          .toMap
        println(s"QueuePax: $queuePax, Expected: $expected")
        queuePax == expected
    }

    crunch.liveArrivalsInput.complete()

    success
  }

  "Given a VoyageManifest with 2 transfers and one Eea Passport " +
    "When I crunch the flight with 10 pax minus 5 transit " +
    "Then I should see the 5 non-transit pax go to the egates" >> {

    val scheduled = "2017-01-01T00:00Z"
    val portCode = PortCode("LHR")
    val flight = ArrivalGenerator.arrival(origin = PortCode("JFK"), schDt = scheduled, iata = "TST001", terminal = T1, actPax = Option(10), tranPax = Option(5))
    val inputManifests = ManifestsFeedSuccess(DqManifests("", Set(
      VoyageManifest(EventTypes.CI, portCode, PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("00:00"),
        manifestPax(5, euPassport) ++ manifestPax(2, inTransitFlag) ++ manifestPax(3, inTransitCountry)
      )
    )))
    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        portCode = portCode,
        terminalProcessingTimes = Map(T1 -> Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60
        )),
        terminals = Seq(T1),
        queues = Map(T1 -> Seq(EeaDesk, EGate, NonEeaDesk))
      ),
      initialPortState = Option(PortState(SortedMap(flight.unique -> ApiFlightWithSplits(flight, Set())), SortedMap[TQM, CrunchMinute](), SortedMap[TM, StaffMinute]()))
    )

    offerAndWait(crunch.manifestsLiveInput, inputManifests)

    val expected = Map(Queues.EeaDesk -> 1.0, Queues.EGate -> 4.0, Queues.NonEeaDesk -> 0.0)

    crunch.portStateTestProbe.fishForMessage(3 seconds) {
      case ps: PortState =>
        val queuePax = ps.crunchMinutes
          .values
          .filter(cm => cm.minute == SDate(scheduled).millisSinceEpoch)
          .map(cm => (cm.queue, cm.paxLoad))
          .toMap

        println(s"QueuePax $queuePax, expected $expected")
        queuePax == expected
    }

    crunch.liveArrivalsInput.complete()

    success
  }

  "Given a voyage manifest then I should get a BestAvailableManifest that matches it" >> {
    val vm = VoyageManifest(EventTypes.CI, PortCode("LHR"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("00:00"), List(
      inTransitFlag,
      inTransitCountry,
      euPassport,
      euIdCard,
      visa,
      visa
    ))

    val result = BestAvailableManifest(vm)

    val expected = BestAvailableManifest(
      ApiSplitsWithHistoricalEGateAndFTPercentages, PortCode("LHR"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), SDate("2017-01-01"),
      List(
        ManifestPassengerProfile(Nationality("GBR"), Option(DocumentType("P")), Option(22), Option(true)),
        ManifestPassengerProfile(Nationality("GBR"), Option(DocumentType("P")), Option(22), Option(true)),
        ManifestPassengerProfile(Nationality("GBR"), Option(DocumentType("P")), Option(22), Option(false)),
        ManifestPassengerProfile(Nationality("ITA"), Option(DocumentType("I")), Option(22), Option(false)),
        ManifestPassengerProfile(Nationality("AFG"), Option(DocumentType("P")), Option(22), Option(false)),
        ManifestPassengerProfile(Nationality("AFG"), Option(DocumentType("P")), Option(22), Option(false))
      )
    )

    result === expected
  }

  "Given a voyage manifest `Passport` instead of `P` for doctype it should still be accepted as a passport doctype" >> {
    val vm = VoyageManifest(EventTypes.CI, PortCode("LHR"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("00:00"), List(
      PassengerInfoJson(Option(DocumentType("P")), Nationality("GBR"), "EEA", Option("22"), Option(PortCode("LHR")), "N", Option(Nationality("GBR")), Option(Nationality("GBR")), None)
    ))

    val result = BestAvailableManifest(vm)

    val expected = BestAvailableManifest(
      ApiSplitsWithHistoricalEGateAndFTPercentages, PortCode("LHR"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), SDate("2017-01-01"),
      List(
        ManifestPassengerProfile(Nationality("GBR"), Option(DocumentType.Passport), Option(22), Option(false))
      )
    )

    result === expected
  }

  "Given a voyage manifest with a UK National and no doctype, Passport should be assumed" >> {
    val vm = VoyageManifest(EventTypes.CI, PortCode("LHR"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("00:00"), List(
      PassengerInfoJson(None, Nationality("GBR"), "EEA", Option("22"), Option(PortCode("LHR")), "N", Option(Nationality("GBR")), Option(Nationality("GBR")), None)
    ))

    val result = BestAvailableManifest(vm)

    val expected = BestAvailableManifest(
      ApiSplitsWithHistoricalEGateAndFTPercentages, PortCode("LHR"), PortCode("JFK"), VoyageNumber("0001"), CarrierCode("BA"), SDate("2017-01-01"),
      List(
        ManifestPassengerProfile(Nationality("GBR"), Option(DocumentType.Passport), Option(22), Option(false))
      )
    )

    result === expected
  }

  "Given a VoyageManifest with 2 transfers, 1 Eea Passport, 1 Eea Id card, and 2 visa nationals " +
    "When I crunch the flight with 4 non-transit pax (10 pax minus 6 transit) " +
    "Then I should see the 4 non-transit pax go to egates (1), eea desk (1), and non-eea (2)" >> {

    val scheduled = "2017-01-01T00:00Z"
    val portCode = PortCode("LHR")

    val flight = ArrivalGenerator.arrival(origin = PortCode("JFK"), schDt = scheduled, iata = "TST001", terminal = T1, actPax = Option(10), tranPax = Option(6))
    val inputManifests = ManifestsFeedSuccess(DqManifests("", Set(
      VoyageManifest(EventTypes.CI, portCode, PortCode("JFK"), VoyageNumber("0001"), CarrierCode("TS"), ManifestDateOfArrival("2017-01-01"), ManifestTimeOfArrival("00:00"), List(
        inTransitFlag,
        inTransitCountry,
        euPassport,
        euIdCard,
        visa,
        visa
      ))
    )))
    val crunch = runCrunchGraph(
      now = () => SDate(scheduled),
      airportConfig = airportConfig.copy(
        portCode = portCode,
        terminalProcessingTimes = Map(T1 -> Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaNonMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60,
          visaNationalToDesk -> 25d / 60
        )),
        terminals = Seq(T1),
        queues = Map(T1 -> Seq(EeaDesk, EGate, NonEeaDesk))
      ),
      initialPortState = Option(PortState(SortedMap(flight.unique -> ApiFlightWithSplits(flight, Set())), SortedMap[TQM, CrunchMinute](), SortedMap[TM, StaffMinute]()))
    )

    offerAndWait(crunch.manifestsLiveInput, inputManifests)

    val expected = Map(Queues.EeaDesk -> 1.2, Queues.EGate -> 0.8, Queues.NonEeaDesk -> 2.0)

    crunch.portStateTestProbe.fishForMessage(10 seconds) {
      case ps: PortState =>
        val queuePax = ps.crunchMinutes
          .values
          .filter(cm => cm.minute == SDate(scheduled).millisSinceEpoch)
          .map(cm => (cm.queue, cm.paxLoad))
          .toMap

        println(s"QueuePax $queuePax, expected $expected")
        queuePax == expected
    }

    success
  }
}

object PassengerInfoGenerator {
  def passengerInfoJson(nationality: Nationality, documentType: DocumentType, issuingCountry: Nationality): PassengerInfoJson = {
    PassengerInfoJson(Option(documentType), issuingCountry, "", Option("22"), Option(PortCode("LHR")), "N", Option(Nationality("GBR")), Option(nationality), None)
  }
}
