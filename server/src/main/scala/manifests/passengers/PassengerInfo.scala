package manifests.passengers

import drt.shared.api._
import drt.shared.{Nationality, PaxType}
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest}
import queueus.B5JPlusWithTransitTypeAllocator

object PassengerInfo {

  val ageRanges: List[AgeRange] = List(
    AgeRange(0, 11),
    AgeRange(12, 24),
    AgeRange(25, 49),
    AgeRange(50, 65),
    AgeRange(65),
  )

  def manifestToAgeRangeCount(manifest: VoyageManifest): Map[PaxAgeRange, Int] =
    excludeDuplicatePax(manifest)
      .PassengerList
      .foldLeft(Map[PaxAgeRange, Int]())((acc: Map[PaxAgeRange, Int], info: PassengerInfoJson) => {

        val maybeRange = info.Age.flatMap(age => ageRanges.find(_.isInRange(age.years)))
        maybeRange match {
          case Some(range) =>

            acc + (range -> (acc.getOrElse(range, 0) + 1))
          case None =>
            acc + (UnknownAge -> (acc.getOrElse(UnknownAge, 0) + 1))
        }
      })

  def manifestToNationalityCount(manifest: VoyageManifest): Map[Nationality, Int] = {
    val unknownNat = Nationality("Unknown")

    excludeDuplicatePax(manifest)
      .PassengerList
      .foldLeft(Map[Nationality, Int]())((acc: Map[Nationality, Int], info: PassengerInfoJson) => {

        info.NationalityCountryCode match {
          case Some(nationality) =>
            acc + (nationality -> (acc.getOrElse(nationality, 0) + 1))

          case None =>
            acc + (unknownNat -> (acc.getOrElse(unknownNat, 0) + 1))
        }
      })
  }

  def manifestToPaxTypes(manifest: ManifestLike): Map[PaxType, Int] = {
    manifest.passengers.map(p => B5JPlusWithTransitTypeAllocator(p))
      .groupBy(identity).mapValues(_.size)
  }

  def excludeDuplicatePax(manifest: VoyageManifest): VoyageManifest = manifest.copy(
    PassengerList = BestAvailableManifest.removeDuplicatePax(manifest)
  )

  def excludeTransitPax(manifest: VoyageManifest): VoyageManifest = manifest.copy(
    PassengerList = manifest
      .PassengerList
      .filterNot(_.isInTransit(manifest.ArrivalPortCode))
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
