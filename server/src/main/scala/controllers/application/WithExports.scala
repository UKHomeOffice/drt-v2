package controllers.application

import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import controllers.Application
import controllers.application.exports.{CsvFileStreaming, WithDesksExport, WithFlightsExport, WithSummariesExport}
import drt.shared.CrunchApi._
import drt.users.KeyCloakGroups
import play.api.http.HttpEntity
import play.api.mvc._
import services.exports.StaffRequirementExports
import services.graphstages.Crunch.europeLondonTimeZone
import uk.gov.homeoffice.drt.auth.Roles.{ForecastView, ManageUsers}
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.language.postfixOps


trait WithExports extends WithDesksExport with WithFlightsExport with WithSummariesExport {
  self: Application =>

//  def localLastMidnight(pointInTime: String): SDateLike = SDate(pointInTime.toLong).getLocalLastMidnight

//  def terminal(terminalName: String): Terminal = Terminal(terminalName)

  def exportUsers: Action[AnyContent] = authByRole(ManageUsers) {
    Action.async { request =>
      val client = keyCloakClient(request.headers)
      client
        .getGroups
        .flatMap(groupList => KeyCloakGroups(groupList, client).usersWithGroupsCsvContent)
        .map(csvContent => Result(
          ResponseHeader(200, Map("Content-Disposition" -> s"attachment; filename=users-with-groups.csv")),
          HttpEntity.Strict(ByteString(csvContent), Option("application/csv"))
        ))
    }
  }

  private val sixMonthsDays = 180

  def exportForecastWeekToCSV(startDay: String, terminalName: String): Action[AnyContent] = authByRole(ForecastView) {
    val terminal = Terminal(terminalName)
    Action.async {
      val start = SDate(startDay.toLong, europeLondonTimeZone)
      val end = start.addDays(sixMonthsDays)
      val rowHeaders = Seq("") ++ (0 until 96).map(qh => start.addMinutes(qh * 15).toHoursAndMinutes)
      val staffingProvider = StaffRequirementExports.staffingForLocalDateProvider(terminal, ctrl.staffMinutesProvider)
      val makeHourlyStaffing = StaffRequirementExports.toHourlyStaffing(staffingProvider, 15)

      StaffRequirementExports
        .queuesProvider(ctrl.crunchMinutesProvider)(start.toLocalDate, end.toLocalDate, terminal)
        .mapAsync(1) {
          case (date, minutes) => makeHourlyStaffing(date, minutes)
        }
        .runWith(Sink.seq)
        .map { seqs =>
          val rows = seqs.transpose.map { x =>
            x.map(t => s"${t._1},${t._2},${t._3}").mkString(",")
          }
          rowHeaders.zip(rows).map { case (header, data) => s"$header,$data" }.mkString("\n")
        }
        .map { csvData =>
          val fileName = f"${airportConfig.portCode}-$terminal-forecast-export-headlines-${start.getFullYear}-${start.getMonth}%02d-${start.getDate}%02d"
          CsvFileStreaming.csvFileResult(fileName, csvData)
        }

    }
  }

  def exportForecastWeekHeadlinesToCSV(startDay: String,
                                       terminalName: String): Action[AnyContent] = authByRole(ForecastView) {
    val terminal = Terminal(terminalName)
    Action.async {
      timedEndPoint(s"Export planning headlines", Option(s"$terminal")) {
        val start = SDate(startDay.toLong, europeLondonTimeZone)
        val end = start.addDays(sixMonthsDays)
        val queues = ctrl.airportConfig.queuesByTerminal(Terminal(terminalName))
        val queueNames = Queues.inOrder(queues).map(Queues.displayName)
        val rowHeaders = Seq("Date", "Total pax") ++ queueNames ++ Seq("Total workload")
        val makeHeadlines = StaffRequirementExports.toPassengerHeadlines(queues)

        StaffRequirementExports
          .queuesProvider(ctrl.crunchMinutesProvider)(start.toLocalDate, end.toLocalDate, terminal)
          .map {
            case (date, minutes) => makeHeadlines(date, minutes)
          }
          .prepend(Source(List(rowHeaders))).runWith(Sink.seq).map(r => r.transpose.map(_.mkString(",")).mkString("\n"))
          .map { csvData =>
            val fileName = f"${airportConfig.portCode}-$terminal-forecast-export-headlines-${start.getFullYear}-${start.getMonth}%02d-${start.getDate}%02d"
            CsvFileStreaming.csvFileResult(fileName, csvData)
          }
      }
    }
  }

//  private def portStateForTerminal(terminal: Terminal,
//                                   endOfForecast: SDateLike,
//                                   startOfForecast: SDateLike): Future[PortState] =
//    ctrl.portStateActor
//      .ask(GetStateForTerminalDateRange(startOfForecast.millisSinceEpoch, endOfForecast.millisSinceEpoch, terminal))(new Timeout(30 seconds))
//      .mapTo[PortState]
//      .recover {
//        case t =>
//          log.error("Failed to get PortState", t)
//          PortState.empty
//      }

  def startAndEndForDay(startDay: MillisSinceEpoch, numberOfDays: Int): (SDateLike, SDateLike) = {
    val startOfWeekMidnight = SDate(startDay).getLocalLastMidnight
    val endOfForecast = startOfWeekMidnight.addDays(numberOfDays)

    (startOfWeekMidnight, endOfForecast)
  }
}
