package services.exports

import akka.NotUsed
import akka.stream.scaladsl.Source
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import drt.shared.api.Arrival
import services.exports.flights.templates.{ActualApiFlightWithSplitsExportTemplate, FlightWithSplitsExportTemplate}
import services.graphstages.Crunch

object StreamingFlightsExport {

  def toCsvStreamWithoutActualApi(flightsStream: Source[FlightsWithSplits, NotUsed]): Source[String, NotUsed] =
    toCsvStreamFromTemplate(FlightWithSplitsExportTemplate(timeZone = Crunch.europeLondonTimeZone))(flightsStream)

  def toCsvStreamFromTemplate(template: FlightExportTemplateLike)
                             (flightsStream: Source[FlightsWithSplits, NotUsed]): Source[String, NotUsed] =
    toCsvStream(flightsStream, template.row, template.headings)


  def toCsvStream(flightsStream: Source[FlightsWithSplits, NotUsed],
                  csvRowFn: ApiFlightWithSplits => String,
                  headers: String
                 ): Source[String, NotUsed] =
    flightsStream
      .map(fws => fwsToCsv(fws, csvRowFn))
      .prepend(Source(List(headers + "\n")))


  def fwsToCsv(fws: FlightsWithSplits, csvRowFn: ApiFlightWithSplits => String): String =
    uniqueArrivalsWithCodeShares(fws.flights.values.toSeq)
      .sortBy {
        case (fws, _) => (fws.apiFlight.PcpTime, fws.apiFlight.VoyageNumber.numeric, fws.apiFlight.Origin.iata)
      }
      .map {
        case (fws, _) => csvRowFn(fws) + "\n"
      }
      .mkString

  def toCsvStreamWithActualApi(flightsStream: Source[FlightsWithSplits, NotUsed]): Source[String, NotUsed] =
    toCsvStreamFromTemplate(ActualApiFlightWithSplitsExportTemplate(timeZone = Crunch.europeLondonTimeZone))(flightsStream)

  val uniqueArrivalsWithCodeShares: Seq[ApiFlightWithSplits] => List[(ApiFlightWithSplits, Set[Arrival])] = CodeShares
    .uniqueArrivalsWithCodeShares((f: ApiFlightWithSplits) => identity(f.apiFlight))

}
