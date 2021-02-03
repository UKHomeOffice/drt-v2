package manifests.paxinfo

import drt.shared.{Nationality, PaxTypes}
import manifests.passengers.PassengerInfo
import manifests.paxinfo.ManifestBuilder.manifestWithPassengerAgesAndNats
import org.specs2.mutable.Specification

import scala.collection.immutable.List

class PaxTypeInfoFromManifestSpec extends Specification {

  "Given a voyage manifest with 3 Adult GBR Nationals then I should get Matching PaxTypes" >> {
    val voyageManifest = manifestWithPassengerAgesAndNats(List(
      (Nationality("GBR"), 20),
      (Nationality("GBR"), 20),
      (Nationality("GBR"), 30))
    )

    val result = PassengerInfo.manifestToPaxTypes(voyageManifest)

    val expected = Map(PaxTypes.EeaMachineReadable -> 3)

    result === expected
  }

  "Given a voyage manifest with 2 Adult and 1 child GBR Nationals then I should get Matching PaxTypes" >> {
    val voyageManifest = manifestWithPassengerAgesAndNats(List(
      (Nationality("GBR"), 10),
      (Nationality("GBR"), 20),
      (Nationality("GBR"), 30))
    )

    val result = PassengerInfo.manifestToPaxTypes(voyageManifest)

    val expected = Map(
      PaxTypes.EeaMachineReadable -> 2,
      PaxTypes.EeaBelowEGateAge -> 1,
    )

    result === expected
  }

  "Given a voyage manifest with 2 Adult GBR Nationals and 1 Adult Zimbabwean then I should get Matching PaxTypes" >> {
    val voyageManifest = manifestWithPassengerAgesAndNats(List(
      (Nationality("ZWE"), 20),
      (Nationality("GBR"), 20),
      (Nationality("GBR"), 30))
    )

    val result = PassengerInfo.manifestToPaxTypes(voyageManifest)

    val expected = Map(
      PaxTypes.EeaMachineReadable -> 2,
      PaxTypes.VisaNational -> 1,
    )

    result === expected
  }

}
