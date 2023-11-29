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

class SummariesExportController@Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  val passengersProvider: (LocalDate, Terminal) => Future[Seq[PassengersMinute]] =
    (date, terminal) => {
      val start = SDate(date).millisSinceEpoch
      val end = SDate(date).addDays(1).addMinutes(-1).millisSinceEpoch
      ctrl.queueLoadsRouterActor
        .ask(GetMinutesForTerminalDateRange(start, end, terminal))
        .mapTo[MinutesContainer[PassengersMinute, TQM]]
        .map(_.minutes.map(_.toMinute).toSeq)
    }

  def exportDailyPassengersForDateRangeApi(startLocalDateString: String,
                                           endLocalDateString: String,
                                           terminalName: String): Action[AnyContent] = Action {
    (LocalDate.parse(startLocalDateString), LocalDate.parse(endLocalDateString)) match {
      case (Some(start), Some(end)) =>
        val terminal = Terminal(terminalName)
        val getArrivals = PassengerExports.totalPassengerCountProvider(ctrl.terminalFlightsProvider, ctrl.paxFeedSourceOrder)
        val toRows = PassengerExports.flightsToDailySummaryRow(ctrl.airportConfig.portCode, terminal, start, end, passengersProvider)
        val csvStream = GeneralExport.toCsv(start, end, terminal, getArrivals, toRows)
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
}
