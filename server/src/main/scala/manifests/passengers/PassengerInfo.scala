package manifests.passengers

import drt.shared.api._
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import queueus.B5JPlusWithTransitTypeAllocator
import uk.gov.homeoffice.drt.Nationality
import uk.gov.homeoffice.drt.ports.{PaxAge, PaxType}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

object PassengerInfo {
  val egateAgeEligibilityDateChange = "2023-07-25T00:00:00"
  def ageRangesForDate(scheduled: Option[SDateLike]): List[AgeRange] = {
    val egateEligibilityAgeRanges = scheduled match {
      case Some(date) if date < SDate(egateAgeEligibilityDateChange) =>
        List(AgeRange(0, 11), AgeRange(12, 24))
      case _ =>
        List(AgeRange(0, 9), AgeRange(10, 24))
    }

    egateEligibilityAgeRanges ++
      List(
        AgeRange(25, 49),
        AgeRange(50, 65),
        AgeRange(65),
      )
  }

  def ageRangeForAge(age: PaxAge, scheduled: Option[SDateLike]): PaxAgeRange = ageRangesForDate(scheduled)
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
      .groupBy(_.age.map(ageRangeForAge(_, manifest.scheduleArrivalDateTime)).getOrElse(UnknownAge))
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
      .groupBy(identity).view.mapValues(_.size).toMap
  }

  def manifestToFlightManifestSummary(manifest: VoyageManifest): Option[FlightManifestSummary] =
    manifest
      .maybeKey
      .map(arrivalKey =>
        FlightManifestSummary(
          arrivalKey,
          manifestToAgeRangeCount(manifest),
          manifestToNationalityCount(manifest),
          manifestToPaxTypes(manifest)
        )
      )
}
