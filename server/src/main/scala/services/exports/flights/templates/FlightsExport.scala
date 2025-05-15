package services.exports.flights.templates

import drt.shared.CodeShares
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.models.{ManifestKey, VoyageManifest}
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.LocalDate

trait FlightsExport {

  def headings: String

  def rowValues(fws: ApiFlightWithSplits, maybeManifest: Option[VoyageManifest]): Iterable[String]

  def start: LocalDate

  def end: LocalDate

  def terminals: Seq[Terminal]

  val standardFilter: (ApiFlightWithSplits, Seq[Terminal]) => Boolean = (fws, terminals) => terminals.contains(fws.apiFlight.Terminal)

  val flightsFilter: (ApiFlightWithSplits, Seq[Terminal]) => Boolean

  val paxFeedSourceOrder: List[FeedSource]

  lazy val uniqueArrivalsWithCodeShares: Seq[ApiFlightWithSplits] => Iterable[ApiFlightWithSplits] =
    CodeShares.uniqueArrivals(paxFeedSourceOrder)

  private def flightToCsvRow(fws: ApiFlightWithSplits, maybeManifest: Option[VoyageManifest]): String = rowValues(fws, maybeManifest).mkString(",")

  def csvStream(flightsStream: Source[(Iterable[ApiFlightWithSplits], VoyageManifests), NotUsed]): Source[String, NotUsed] =
    filterAndSort(flightsStream)
      .map { case (fws, maybeManifest) => flightToCsvRow(fws, maybeManifest) + "\n" }
      .prepend(Source(List(headings + "\n")))

  private def filterAndSort(flightsStream: Source[(Iterable[ApiFlightWithSplits], VoyageManifests), NotUsed],
                           ): Source[(ApiFlightWithSplits, Option[VoyageManifest]), NotUsed] =
    flightsStream.mapConcat { case (flights, manifests) =>
      uniqueArrivalsWithCodeShares(flights.toSeq)
        .filter(fws => flightsFilter(fws, terminals))
        .toSeq
        .sortBy(_.apiFlight.PcpTime.getOrElse(0L))
        .map { fws =>
          val maybeManifest = manifests.manifests.find(_.maybeKey.exists(_ == ManifestKey(fws.apiFlight)))
          (fws, maybeManifest)
        }
    }
}
