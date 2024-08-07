package drt.client.components

import drt.shared.ArrivalKey
import drt.shared.api.{FlightManifestSummary, PaxAgeRange}
import japgolly.scalajs.react.vdom.{VdomElement, html_<^}
import japgolly.scalajs.react.vdom.all.EmptyVdom
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.PaxTypes.VisaNational
import japgolly.scalajs.react.vdom.html_<^.{<, ^, _}
import scala.scalajs.js

object FlightHighlighter {
  private def getConditionsAndFlaggedSummary(manifestSummary: Option[FlightManifestSummary],
                                          flaggedNationalities: Set[drt.shared.Country],
                                          flaggedAgeGroups: Set[PaxAgeRange],
                                          showNumberOfVisaNationals: Boolean) = {
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
                                  flightManifestSummaries: Map[ArrivalKey, FlightManifestSummary],
                                  flaggedNationalities: Set[drt.shared.Country],
                                  flaggedAgeGroups: Set[PaxAgeRange],
                                  showNumberOfVisaNationals: Boolean,
                                  showHighlightedRows: Boolean,
                                  showRequireAllSelected: Boolean): Int = {
    val flightManifestSummary: Seq[FlightManifestSummary] = sortedFlights.flatMap {
      case (flightWithSplits, _) => flightManifestSummaries.get(ArrivalKey(flightWithSplits.apiFlight))
    }
    val flaggedInSummary: Seq[Option[Int]] = flightManifestSummary.map { manifestSummary =>

      val conditionsAndFlaggedSummary: Seq[(Boolean, Set[Option[Int]])] = getConditionsAndFlaggedSummary(Some(manifestSummary),
        flaggedNationalities,
        flaggedAgeGroups,
        showNumberOfVisaNationals)
//      val flaggedNationalitiesInSummary: Set[Option[Int]] =
//        flaggedNationalities
//          .map { country =>
//            manifestSummary.nationalities.find(n => n._1.code == country.threeLetterCode).map(_._2)
//          }
//
//      val flaggedAgeGroupInSummary: Set[Option[Int]] = flaggedAgeGroups.map { ageRanges =>
//        manifestSummary.ageRanges.find(n => n._1 == ageRanges).map(_._2)
//      }
//
//      val visaNationalsInSummary: Set[Option[Int]] = Set(manifestSummary.paxTypes.get(VisaNational))
//
//      val conditionsAndFlaggedSummary: Seq[(Boolean, Set[Option[Int]])] = List(
//        (flaggedNationalities.nonEmpty, flaggedNationalitiesInSummary),
//        (flaggedAgeGroups.nonEmpty, flaggedAgeGroupInSummary),
//        (showNumberOfVisaNationals, visaNationalsInSummary)
//      )

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

//    val flaggedNationalitiesInSummary: Set[Option[Int]] = flaggedNationalities.map { country =>
//      manifestSummary.map(_.nationalities.find(n => n._1.code == country.threeLetterCode).map(_._2).getOrElse(0))
//    }
//    val flaggedAgeGroupInSummary: Set[Option[Int]] = flaggedAgeGroups.map { ageRanges =>
//      manifestSummary.map(_.ageRanges.find(n => n._1 == ageRanges).map(_._2).getOrElse(0))
//    }
//    val visaNationalsInSummary: Option[Int] = manifestSummary.map(_.paxTypes.getOrElse(VisaNational, 0))
//
//    val conditionsAndFlaggedSummary: Seq[(Boolean, Set[Option[Int]])] = List(
//      (flaggedNationalities.nonEmpty, flaggedNationalitiesInSummary),
//      (flaggedAgeGroups.nonEmpty, flaggedAgeGroupInSummary),
//      (showNumberOfVisaNationals, Set(visaNationalsInSummary))
//    )

    val conditionsAndFlaggedSummary = getConditionsAndFlaggedSummary(manifestSummary, flaggedNationalities, flaggedAgeGroups, showNumberOfVisaNationals)
    val trueConditionsAndChips: Seq[(Boolean, Set[Option[Int]])] = conditionsAndFlaggedSummary.filter(_._1)

    val isFlaggedInSummaryExists = conditionsAndFlaggedSummary.filter(_._1).flatMap(_._2).filter(_.isDefined).flatten.sum > 0

//      flaggedNationalitiesInSummary.flatten.sum > 0 && flaggedNationalities.nonEmpty ||
//      flaggedAgeGroupInSummary.flatten.sum > 0 && flaggedAgeGroups.nonEmpty ||
//      visaNationalsInSummary.getOrElse(0) > 0 && showNumberOfVisaNationals

    (showHighlightedRows, showRequireAllSelected) match {
      case (_, true) =>
        if (trueConditionsAndChips.map(_._2).forall(_.exists(a => a.sum > 0)))
          Some(true)
        else None
      case (true, false) => if (isFlaggedInSummaryExists) {
        Some(true)
      } else None
      case (_, _) =>
        Some(isFlaggedInSummaryExists)
    }
  }


//  def highlightedChipLogic(showNumberOfVisaNationals: Boolean,
//                           flaggedAgeGroups: Set[PaxAgeRange],
//                           flaggedNationalities: Set[drt.shared.Country],
//                           manifestSummary: Option[FlightManifestSummary]): Seq[Boolean] = {
////    manifestSummary.map { summary =>
//
////      val flaggedNationalitiesExits: Set[Boolean] = flaggedNationalities.map { country =>
////        summary.nationalities.find(n => n._1.code == country.threeLetterCode).map(_._2).getOrElse(0) > 0
////      }
////
////      val flaggedAgeGroupsExists: Set[Boolean] = flaggedAgeGroups.map { ageRanges =>
////        summary.ageRanges.find(n => n._1 == ageRanges).map(_._2).getOrElse(0) > 0
////      }
////
////      val visaNationalsExists: Boolean = showNumberOfVisaNationals && summary.paxTypes.getOrElse(VisaNational, 0) > 0
////
////      val conditionsAndChips: Seq[(Boolean, Set[Boolean])] = List(
////        (flaggedNationalities.nonEmpty, flaggedNationalitiesExits),
////        (flaggedAgeGroups.nonEmpty, flaggedAgeGroupsExists),
////        (showNumberOfVisaNationals, Set(visaNationalsExists)),
////      )
//
//      val conditionsAndFlaggedSummary = getConditionsAndFlaggedSummary(manifestSummary, flaggedNationalities, flaggedAgeGroups, showNumberOfVisaNationals)
//
//      conditionsAndFlaggedSummary.filter(_._1).flatMap(_._2).filter(_.isDefined).flatten.sum > 0
////      val paxExistSets: Seq[(Boolean, Set[Option[Int]])] = conditionsAndFlaggedSummary.filter(_._1)
////      paxExistSets.map(_._2.exists(s => s > 0))
////    }.getOrElse(Seq.empty[Boolean])
//
//    if (props.showRequireAllSelected && conditionsAndFlaggedSummary.nonEmpty) {
//      conditionsAndFlaggedSummary.forall(_ == true)
//    } else false
//  }

   def highlightedChips(showNumberOfVisaNationals: Boolean,
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
