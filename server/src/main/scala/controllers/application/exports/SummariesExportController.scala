package controllers.application.exports

import actors.PartitionedPortStateActor.{GetMinutesForTerminalDateRange, GetStateForDateRange}
import akka.NotUsed
import akka.pattern.ask
import akka.stream.scaladsl.Source
import com.google.inject.Inject
import controllers.application.AuthController
import controllers.application.exports.CsvFileStreaming.{makeFileName, sourceToCsvResponse}
import drt.shared.CrunchApi.{MinutesContainer, PassengersMinute}
import drt.shared.TQM
import play.api.mvc._
import services.exports.{GeneralExport, PassengerExports}
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class SummariesExportController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  private val terminalPassengersProvider: Terminal => LocalDate => Future[Seq[PassengersMinute]] =
    terminal => date => {
      val start = SDate(date).millisSinceEpoch
      val end = SDate(date).addDays(1).addMinutes(-1).millisSinceEpoch
      ctrl.queueLoadsRouterActor
        .ask(GetMinutesForTerminalDateRange(start, end, terminal))
        .mapTo[MinutesContainer[PassengersMinute, TQM]]
        .map(_.minutes.map(_.toMinute).toSeq)
    }

  private val portPassengersProvider: LocalDate => Future[Seq[PassengersMinute]] =
    date => {
      val start = SDate(date).millisSinceEpoch
      val end = SDate(date).addDays(1).addMinutes(-1).millisSinceEpoch
      ctrl.queueLoadsRouterActor
        .ask(GetStateForDateRange(start, end))
        .mapTo[MinutesContainer[PassengersMinute, TQM]]
        .map(_.minutes.map(_.toMinute).toSeq)
    }

  def exportDailyTerminalPassengersForDateRangeApi(startLocalDateString: String,
                                                   endLocalDateString: String,
                                                   terminalName: String): Action[AnyContent] = Action {
    val terminal = Terminal(terminalName)
    val maybeTerminal = Option(terminal)
    val flightsProvider = ctrl.terminalFlightsProvider(terminal)
    val paxProvider = terminalPassengersProvider(terminal)

    exportDailyForDateRange(startLocalDateString, endLocalDateString, maybeTerminal, flightsProvider, paxProvider)
  }

  def exportDailyPortPassengersForDateRangeApi(startLocalDateString: String, endLocalDateString: String): Action[AnyContent] = Action {
    val maybeTerminal = None
    val flightsProvider = ctrl.portFlightsProvider
    val paxProvider = portPassengersProvider

    exportDailyForDateRange(startLocalDateString, endLocalDateString, maybeTerminal, flightsProvider, paxProvider)
  }

  private def exportDailyForDateRange(startLocalDateString: String,
                                      endLocalDateString: String,
                                      maybeTerminal: Option[Terminal],
                                      flightsProvider: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
                                      paxProvider: LocalDate => Future[Seq[PassengersMinute]]
                                     ): Result = {
    (LocalDate.parse(startLocalDateString), LocalDate.parse(endLocalDateString)) match {
      case (Some(start), Some(end)) =>
        implicit val toRows: Seq[(LocalDate, Int, Iterable[PassengersMinute])] => String =
          PassengerExports.paxMinutesToDailyRows(ctrl.airportConfig.portCode, maybeTerminal)

        val totalPassengersForDate = PassengerExports.totalPassengerCountProvider(flightsProvider, ctrl.paxFeedSourceOrder)
        val paxMinutesProvider = PassengerExports.dailyPassengerMinutes(start, end, paxProvider)
        val csvStream = GeneralExport.toDailyRows(start, end, totalPassengersForDate, paxMinutesProvider)
        val fileName = makeFileName("passengers", maybeTerminal, start, end, airportConfig.portCode)
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

  private def exportSummaryForDateRange(startLocalDateString: String,
                                        endLocalDateString: String,
                                        maybeTerminal: Option[Terminal],
                                        flightsProvider: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
                                        paxProvider: LocalDate => Future[Seq[PassengersMinute]]
                                       ): Result = {
    (LocalDate.parse(startLocalDateString), LocalDate.parse(endLocalDateString)) match {
      case (Some(start), Some(end)) =>
        implicit val toRows: Seq[(LocalDate, Int, Iterable[PassengersMinute])] => String =
          PassengerExports.paxMinutesToDailyRows(ctrl.airportConfig.portCode, maybeTerminal)

        val totalPassengersForDate: (LocalDate, LocalDate) => Source[(LocalDate, Int), NotUsed] =
          PassengerExports.totalPassengerCountProvider(flightsProvider, ctrl.paxFeedSourceOrder)

        val paxMinutesProvider: (LocalDate, Int) => Future[Seq[(LocalDate, Int, Iterable[PassengersMinute])]] =
          (date, total) => PassengerExports.dailyPassengerMinutes(start, end, paxProvider)

        val csvStream = GeneralExport.toTotalsRow(start, end, totalPassengersForDate, paxMinutesProvider)

        val fileName = makeFileName("passengers", maybeTerminal, start, end, airportConfig.portCode)
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
