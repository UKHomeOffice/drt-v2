package manifests.passengers

import drt.shared.PaxType
import drt.shared.api._
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import queueus.B5JPlusWithTransitTypeAllocator
import uk.gov.homeoffice.drt.Nationality

object PassengerInfo {

  val ageRanges: List[AgeRange] = List(
    AgeRange(0, 11),
    AgeRange(12, 24),
    AgeRange(25, 49),
    AgeRange(50, 65),
    AgeRange(65),
  )

  def manifestToAgeRangeCount(manifest: VoyageManifest): Map[PaxAgeRange, Int] =
    manifest
      .uniquePassengers
      .foldLeft(Map[PaxAgeRange, Int]())((acc: Map[PaxAgeRange, Int], info) => {
        val maybeRange = info.age.flatMap(age => ageRanges.find(_.isInRange(age.years)))
        maybeRange match {
          case Some(range) =>
            acc + (range -> (acc.getOrElse(range, 0) + 1))
          case None =>
            acc + (UnknownAge -> (acc.getOrElse(UnknownAge, 0) + 1))
        }
      })

  def manifestToNationalityCount(manifest: VoyageManifest): Map[Nationality, Int] = {
    val unknownNat = Nationality("Unknown")

    manifest
      .uniquePassengers
      .foldLeft(Map[Nationality, Int]())((acc: Map[Nationality, Int], info: ManifestPassengerProfile) => {
        info.nationality match {
          case nationality if nationality.code.nonEmpty =>
            acc + (nationality -> (acc.getOrElse(nationality, 0) + 1))
          case _ =>
            acc + (unknownNat -> (acc.getOrElse(unknownNat, 0) + 1))
        }
      })
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
