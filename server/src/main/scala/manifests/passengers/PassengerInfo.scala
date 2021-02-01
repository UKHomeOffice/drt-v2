package manifests.passengers

import drt.shared.api._
import drt.shared.{Nationality, PaxType}
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest}
import queueus.DefaultPaxTypeAllocator

object PassengerInfo {

  val ageRanges: List[AgeRange] = List(
    AgeRange(0, 11),
    AgeRange(12, 24),
    AgeRange(25, 49),
    AgeRange(50, 65),
    AgeRange(65),
  )

  def manifestToAgeRangeCount(manifest: VoyageManifest): Map[AgeRange, Int] =
    excludeDuplicatePax(manifest)
      .PassengerList
      .foldLeft(Map[AgeRange, Int]())((acc: Map[AgeRange, Int], info: PassengerInfoJson) => {

        val maybeRange = info.Age.flatMap(age => ageRanges.find(_.isInRange(age.years)))
        maybeRange match {
          case Some(range) =>

            acc + (range -> (acc.getOrElse(range, 0) + 1))
          case None => acc
        }
      })

  def manifestToNationalityCount(manifest: VoyageManifest): Map[Nationality, Int] = {
    excludeDuplicatePax(manifest)
      .PassengerList
      .foldLeft(Map[Nationality, Int]())((acc: Map[Nationality, Int], info: PassengerInfoJson) => {

        info.NationalityCountryCode match {
          case Some(nationality) =>

            acc + (nationality -> (acc.getOrElse(nationality, 0) + 1))
          case None => acc
        }
      })
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
          manifestToPassengerTypes(manifest)
        )
      )

  def manifestToPassengerTypes(manifest: VoyageManifest): Map[PaxType, Int] =
    bestAvailableManifestToPaxTypes(BestAvailableManifest(manifest))

  def bestAvailableManifestToPaxTypes(bestAvailableManifest: BestAvailableManifest): Map[PaxType, Int] = {
    bestAvailableManifest.passengerList.map(p => DefaultPaxTypeAllocator(bestAvailableManifest)(p))
      .groupBy(identity).mapValues(_.size)
  }

}
