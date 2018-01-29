package services.crunch.workload

import controllers.ArrivalGenerator
import drt.shared.PaxTypes.{EeaMachineReadable, EeaNonMachineReadable}
import drt.shared.Queues._
import drt.shared.SplitRatiosNs.SplitSources._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared._
import org.specs2.mutable.Specification
import passengersplits.core.SplitsCalculator
import services.SplitsProvider
import services.crunch.VoyageManifestGenerator._


class SplitsSpec extends Specification {
  val eeaMrToDesk = SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EeaDesk), 0.5)
  val eeaMrToEgate = SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, EGate), 0.5)
  val splitRatios = SplitRatios(splits = List(eeaMrToDesk, eeaMrToEgate), origin = Historical)

  val eeaMrSplitsProvider: SplitsProvider.SplitProvider = (_, _) => Option(splitRatios)

  "Given an arrival and its voyage manifest and historic splits for egates and fast track " +
    "When I ask for the arrival's splits " +
    "Then the total nationalities count should equal the number of passengers in the manifest" >> {

    val arrival = ArrivalGenerator.apiFlight()
    val paxInfos = List(
      euIdCard, euIdCard,
      euPassport, euPassport)
    val manifest = voyageManifest(paxInfos = paxInfos)

    val splitsCalc = SplitsCalculator("LHR", eeaMrSplitsProvider, Set())

    val splits = splitsCalc.splitsForArrival(manifest, arrival)

    val expectedSplits = ApiSplits(
      Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 1, Some(Map("GBR" -> 1.0))),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 1, Some(Map("GBR" -> 1.0))),
        ApiPaxTypeAndQueueCount(EeaNonMachineReadable, EeaDesk, 2.0, Some(Map("GBR" -> 2.0)))),
      ApiSplitsWithHistoricalEGateAndFTPercentages, Some(DqEventCodes.DepartureConfirmed), PaxNumbers)

    splits === expectedSplits
  }
}