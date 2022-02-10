package manifests.passengers

import drt.shared.api._
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import queueus.B5JPlusWithTransitTypeAllocator
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.ports.{PaxAge, PaxType}

object PassengerInfo {

  val ageRanges: List[AgeRange] = List(
    AgeRange(0, 11),
    AgeRange(12, 24),
    AgeRange(25, 49),
    AgeRange(50, 65),
    AgeRange(65),
  )

  def ageRangeForAge(age: PaxAge): PaxAgeRange = ageRanges
    .find { ar =>
      val withinTop = ar.top match {
        case Some(top) => age.years <= top
        case None => true
      }
      val withinBottom = ar.bottom <= age.years

      withinBottom && withinTop
    }
    .getOrElse(UnknownAge)

  def manifestToAgeRangeCount(manifest: VoyageManifest): Map[PaxAgeRange, Int] = {
    manifest
      .uniquePassengers
      .groupBy(_.age.map(ageRangeForAge).getOrElse(UnknownAge))
      .map {
        case (ageRange, paxProfiles) => (ageRange, paxProfiles.size)
      }
  }

  def manifestToNationalityCount(manifest: VoyageManifest): Map[Nationality, Int] =
    manifest
      .uniquePassengers
      .groupBy(_.nationality)
      .map {
        case (nat, paxProfiles) =>
          val natWithUnknown = if (nat.code.isEmpty) Nationality("Unknown") else nat
          (natWithUnknown, paxProfiles.size)
      }

  def manifestToPaxTypes(manifest: ManifestLike): Map[PaxType, Int] = {
    manifest.uniquePassengers.map(p => B5JPlusWithTransitTypeAllocator(p))
      .groupBy(identity).mapValues(_.size)
  }

  def excludeDuplicatePax(manifest: VoyageManifest): VoyageManifest = manifest.copy(
    PassengerList = BestAvailableManifest.removeDuplicatePax(manifest)
  )

  def manifestToPassengerInfoSummary(manifest: VoyageManifest): Option[PassengerInfoSummary] =
    manifest
      .maybeKey
      .map(arrivalKey =>
        PassengerInfoSummary(
          arrivalKey,
          manifestToAgeRangeCount(manifest),
          manifestToNationalityCount(manifest),
          manifestToPaxTypes(manifest)
        )
      )
}
