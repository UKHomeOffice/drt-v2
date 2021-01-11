package manifests.paxinfo

import drt.shared._
import drt.shared.api.{AgeRange, PassengerInfoSummary}
import manifests.passengers.PassengerInfo
import manifests.paxinfo.ManifestBuilder.manifestWithPassengerAgesAndNats
import org.specs2.mutable.Specification
import services.SDate

import scala.collection.immutable.List


class PassengerInfoSummaryFromManifestSpec extends Specification {

  "When extracting passenger info " +
    "Given a manifest with multiple GB passengers aged 10, 20 and 30" +
    "Then I should get a matching PassengerInfoSummary" >> {

    val voyageManifest = manifestWithPassengerAgesAndNats(List(
      (Nationality("GBR"), 10),
      (Nationality("GBR"), 20),
      (Nationality("GBR"), 30))
    )

    val result = PassengerInfo.manifestToPassengerInfoSummary(voyageManifest)

    val expected = Option(PassengerInfoSummary(
      ArrivalKey(PortCode("JFK"), VoyageNumber(1), SDate("2020-11-09T00:00").millisSinceEpoch),
      Map(AgeRange(0, Option(11)) -> 1, AgeRange(12, Option(24)) -> 1, AgeRange(25, Option(49)) -> 1),
      Map(Nationality("GBR") -> 3),
      Map(
        PaxTypes.EeaMachineReadable -> 2,
        PaxTypes.EeaBelowEGateAge -> 1,
      )
    ))

    result === expected
  }


}
