package controllers.application.exports

import actors.PartitionedPortStateActor.GetMinutesForTerminalDateRange
import akka.pattern.ask
import controllers.Application
import controllers.application.exports.CsvFileStreaming.{makeFileName, sourceToCsvResponse}
import drt.shared.CrunchApi.{MinutesContainer, PassengersMinute}
import drt.shared.TQM
import play.api.mvc._
import services.exports.{FlightExports, GeneralExport, PassengerExports}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait WithSummariesExport {
  self: Application =>

  val passengersProvider: (LocalDate, Terminal) => Future[Seq[PassengersMinute]] =
    (date, terminal) => {
      val start = SDate(date).millisSinceEpoch
      val end = SDate(date).addDays(1).addMinutes(-1).millisSinceEpoch
      ctrl.queueLoadsRouterActor
        .ask(GetMinutesForTerminalDateRange(start, end, terminal))
        .mapTo[MinutesContainer[PassengersMinute, TQM]]
        .map(_.minutes.map(_.toMinute).toSeq)
    }

  def exportDailyPassengersForDateRangeCSVv2(startLocalDateString: String,
                                             endLocalDateString: String,
                                             terminalName: String): Action[AnyContent] = Action {
    (LocalDate.parse(startLocalDateString), LocalDate.parse(endLocalDateString)) match {
      case (Some(start), Some(end)) =>
        val terminal = Terminal(terminalName)
        val getArrivals = PassengerExports.totalPassengerCountProvider(ctrl.terminalFlightsProvider, ctrl.paxFeedSourceOrder)
        val toRows = PassengerExports.flightsToDailySummaryRow(ctrl.airportConfig.portCode, terminal, start, end, /*ctrl.paxFeedSourceOrder,*/ passengersProvider)
        val csvStream = GeneralExport.toCsv(start, end, terminal, getArrivals, toRows)
        val fileName = makeFileName("passengers", terminal, start, end, airportConfig.portCode)
        Try(sourceToCsvResponse(csvStream, fileName)) match {
          case Success(value) => value
          case Failure(t) =>
            log.error("Failed to get CSV export", t)
            BadRequest("Failed to get CSV export")
        }
      case _ =>
        BadRequest("Invalid date format for start or end date")
    }
  }
}
