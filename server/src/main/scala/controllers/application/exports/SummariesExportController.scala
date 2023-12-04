package controllers.application.exports

import actors.PartitionedPortStateActor.GetMinutesForTerminalDateRange
import akka.pattern.ask
import com.google.inject.Inject
import controllers.application.AuthController
import controllers.application.exports.CsvFileStreaming.{makeFileName, sourceToCsvResponse}
import drt.shared.CrunchApi.{MinutesContainer, PassengersMinute}
import drt.shared.TQM
import play.api.mvc._
import services.exports.{GeneralExport, PassengerExports}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class SummariesExportController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  val terminalPassengersProvider: Terminal => LocalDate => Future[Seq[PassengersMinute]] =
    terminal => date => {
      val start = SDate(date).millisSinceEpoch
      val end = SDate(date).addDays(1).addMinutes(-1).millisSinceEpoch
      ctrl.queueLoadsRouterActor
        .ask(GetMinutesForTerminalDateRange(start, end, terminal))
        .mapTo[MinutesContainer[PassengersMinute, TQM]]
        .map(_.minutes.map(_.toMinute).toSeq)
    }

  val portPassengersProvider: LocalDate => Future[Seq[PassengersMinute]] =
    date => {
      val start = SDate(date).millisSinceEpoch
      val end = SDate(date).addDays(1).addMinutes(-1).millisSinceEpoch
      ctrl.queueLoadsRouterActor
        .ask(GetMinutesForTerminalDateRange(start, end, terminal))
        .mapTo[MinutesContainer[PassengersMinute, TQM]]
        .map(_.minutes.map(_.toMinute).toSeq)
    }

  def exportDailyTerminalPassengersForDateRangeApi(startLocalDateString: String,
                                                   endLocalDateString: String,
                                                   terminalName: String): Action[AnyContent] = Action {
    (LocalDate.parse(startLocalDateString), LocalDate.parse(endLocalDateString)) match {
      case (Some(start), Some(end)) =>
        val terminal = Terminal(terminalName)
        val getPassengers = PassengerExports.totalPassengerCountProvider(ctrl.terminalFlightsProvider(terminal), ctrl.paxFeedSourceOrder)
        val toRows = PassengerExports.flightsToDailySummaryRow(ctrl.airportConfig.portCode, terminal, start, end, terminalPassengersProvider(terminal))
        val csvStream = GeneralExport.toCsv(start, end, getPassengers, toRows)
        val fileName = makeFileName("passengers", terminal, start, end, airportConfig.portCode)
        Try(sourceToCsvResponse(csvStream, fileName)) match {
          case Success(value) => value
          case Failure(t) =>
            log.error(s"Failed to get CSV export${t.getMessage}")
            BadRequest("Failed to get CSV export")
        }
      case _ =>
        BadRequest("Invalid date format for start or end date")
    }
  }

  def exportDailyPortPassengersForDateRangeApi(startLocalDateString: String, endLocalDateString: String): Action[AnyContent] = Action {
    (LocalDate.parse(startLocalDateString), LocalDate.parse(endLocalDateString)) match {
      case (Some(start), Some(end)) =>
        val getPassengers = PassengerExports.totalPassengerCountProvider(ctrl.portFlightsProvider, ctrl.paxFeedSourceOrder)
        val toRows = PassengerExports.flightsToDailySummaryRow(ctrl.airportConfig.portCode, start, end, terminalPassengersProvider)
        val csvStream = GeneralExport.toCsv(start, end, getPassengers, toRows)
        val fileName = makeFileName("passengers", start, end, airportConfig.portCode)
        Try(sourceToCsvResponse(csvStream, fileName)) match {
          case Success(value) => value
          case Failure(t) =>
            log.error(s"Failed to get CSV export${t.getMessage}")
            BadRequest("Failed to get CSV export")
        }
      case _ =>
        BadRequest("Invalid date format for start or end date")
    }
  }
}
