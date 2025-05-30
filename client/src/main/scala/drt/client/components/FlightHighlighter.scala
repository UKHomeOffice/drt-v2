package drt.client.components

import japgolly.scalajs.react.vdom.all.EmptyVdom
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import japgolly.scalajs.react.vdom.{VdomElement, html_<^}
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.models.{FlightManifestSummary, ManifestKey, PaxAgeRange}
import uk.gov.homeoffice.drt.ports.PaxTypes.VisaNational

import scala.scalajs.js

object FlightHighlighter {
  private def getConditionsAndFlaggedSummary(manifestSummary: Option[FlightManifestSummary],
                                             flaggedNationalities: Set[drt.shared.Country],
                                             flaggedAgeGroups: Set[PaxAgeRange],
                                             showNumberOfVisaNationals: Boolean): Seq[(Boolean, Set[Option[Int]])] = {
    val flaggedNationalitiesInSummary: Set[Option[Int]] = flaggedNationalities.map { country =>
      manifestSummary.map(_.nationalities.find(n => n._1.code == country.threeLetterCode).map(_._2).getOrElse(0))
    }
    val flaggedAgeGroupInSummary: Set[Option[Int]] = flaggedAgeGroups.map { ageRanges =>
      manifestSummary.map(_.ageRanges.find(n => n._1 == ageRanges).map(_._2).getOrElse(0))
    }
    val visaNationalsInSummary: Option[Int] = manifestSummary.map(_.paxTypes.getOrElse(VisaNational, 0))

    List(
      (flaggedNationalities.nonEmpty, flaggedNationalitiesInSummary),
      (flaggedAgeGroups.nonEmpty, flaggedAgeGroupInSummary),
      (showNumberOfVisaNationals, Set(visaNationalsInSummary))
    )
  }

  def findHighlightedFlightsCount(sortedFlights: Seq[(ApiFlightWithSplits, Seq[String])],
                                  flightManifestSummaries: Map[ManifestKey, FlightManifestSummary],
                                  flaggedNationalities: Set[drt.shared.Country],
                                  flaggedAgeGroups: Set[PaxAgeRange],
                                  showNumberOfVisaNationals: Boolean,
                                  showHighlightedRows: Boolean,
                                  showRequireAllSelected: Boolean): Int = {
    val flightManifestSummary: Seq[FlightManifestSummary] = sortedFlights.flatMap {
      case (flightWithSplits, _) => flightManifestSummaries.get(ManifestKey(flightWithSplits.apiFlight))
    }
    val flaggedInSummary: Seq[Option[Int]] = flightManifestSummary.map { manifestSummary =>

      val conditionsAndFlaggedSummary: Seq[(Boolean, Set[Option[Int]])] = getConditionsAndFlaggedSummary(Some(manifestSummary),
        flaggedNationalities,
        flaggedAgeGroups,
        showNumberOfVisaNationals)

      (showHighlightedRows, showRequireAllSelected) match {
        case (_, true) =>
          if (conditionsAndFlaggedSummary.map(_._2).forall(_.exists(a => a.sum > 0))) Some(1)
          else None
        case (_, _) =>
          if (conditionsAndFlaggedSummary.filter(_._1).flatMap(_._2).filter(_.isDefined).flatten.sum > 0) Some(1)
          else None
      }
    }

    flaggedInSummary.flatten.sum
  }

  def highlightedFlight(manifestSummary: Option[FlightManifestSummary],
                        flaggedNationalities: Set[drt.shared.Country],
                        flaggedAgeGroups: Set[PaxAgeRange],
                        showNumberOfVisaNationals: Boolean,
                        showHighlightedRows: Boolean,
                        showRequireAllSelected: Boolean): Option[Boolean] = {

    val conditionsAndFlaggedSummary = getConditionsAndFlaggedSummary(manifestSummary, flaggedNationalities, flaggedAgeGroups, showNumberOfVisaNationals)
    val trueConditionsAndChips: Seq[(Boolean, Set[Option[Int]])] = conditionsAndFlaggedSummary.filter(_._1)

    val isFlaggedInSummaryExists = conditionsAndFlaggedSummary.filter(_._1).flatMap(_._2).filter(_.isDefined).flatten.sum > 0

    (showHighlightedRows, showRequireAllSelected) match {
      case (true, true) =>
        if (trueConditionsAndChips.map(_._2).forall(_.exists(a => a.sum > 0)))
          Some(true)
        else None
      case (false, true) =>
        if (trueConditionsAndChips.map(_._2).forall(_.exists(a => a.sum > 0)))
          Some(true)
        else Some(false)
      case (true, false) => if (isFlaggedInSummaryExists) {
        Some(true)
      } else None
      case (_, _) =>
        Some(isFlaggedInSummaryExists)
    }
  }

  def highlightedColumnData(showNumberOfVisaNationals: Boolean,
                            showRequireAllSelected: Boolean,
                            flaggedAgeGroups: Set[PaxAgeRange],
                            flaggedNationalities: Set[drt.shared.Country],
                            manifestSummary: Option[FlightManifestSummary]): html_<^.VdomNode = {
    <.div(^.minWidth := "150px",
      manifestSummary.map { summary =>
        def generateChip(condition: Boolean, pax: Int, label: String): Option[VdomElement] = {
          if (condition && pax > 0) Option(<.div(^.style := js.Dictionary("paddingBottom" -> "5px"), s"$pax $label"))
          else None
        }

        val flaggedNationalitiesChips: Set[Option[VdomElement]] = flaggedNationalities.map { country =>
          val pax = summary.nationalities.find(n => n._1.code == country.threeLetterCode).map(_._2).getOrElse(0)
          generateChip(pax > 0, pax, s"${country.name} (${country.threeLetterCode}) pax")
        }

        val flaggedAgeGroupsChips: Set[Option[VdomElement]] = flaggedAgeGroups.map { ageRanges =>
          val pax = summary.ageRanges.find(n => n._1 == ageRanges).map(_._2).getOrElse(0)
          generateChip(pax > 0, pax, s"pax aged ${ageRanges.title}")
        }

        val visaNationalsChip: Option[VdomElement] = generateChip(showNumberOfVisaNationals, summary.paxTypes.getOrElse(VisaNational, 0), "Visa Nationals")

        val chips: Set[Option[VdomElement]] = flaggedNationalitiesChips ++ flaggedAgeGroupsChips ++ Set(visaNationalsChip)

        val conditionsAndChips: Seq[(Boolean, Set[Option[VdomElement]])] = List(
          (flaggedNationalities.nonEmpty, flaggedNationalitiesChips),
          (flaggedAgeGroups.nonEmpty, flaggedAgeGroupsChips),
          (showNumberOfVisaNationals, Set(visaNationalsChip)),
        )

        val trueConditionsAndChips: Seq[(Boolean, Set[Option[VdomElement]])] = conditionsAndChips.filter(_._1)

        if (showRequireAllSelected) {
          if (trueConditionsAndChips.map(_._2).forall(_.exists(_.nonEmpty)))
            trueConditionsAndChips.flatMap(_._2).flatten.toTagMod
          else EmptyVdom
        } else {
          if (chips.exists(_.isDefined)) {
            <.div(chips.flatten.toTagMod)
          } else EmptyVdom
        }
      }.getOrElse(EmptyVdom))
  }

}
