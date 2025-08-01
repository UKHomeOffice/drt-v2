package controllers.application

import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import com.google.inject.Inject
import controllers.application.exports.CsvFileStreaming
import drt.http.ProdSendAndReceive
import drt.shared.CrunchApi._
import drt.users.{KeyCloakClient, KeyCloakGroups}
import play.api.http.HttpEntity
import play.api.mvc._
import services.exports.StaffRequirementExports
import services.metrics.Metrics
import uk.gov.homeoffice.drt.auth.Roles.{ForecastView, ManageUsers}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.MilliTimes.minutesInADay
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.Future


class ExportsController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def keyCloakClient(headers: Headers): KeyCloakClient with ProdSendAndReceive = {
    val token = headers.get("X-Forwarded-Access-Token")
      .getOrElse(throw new Exception("X-Forwarded-Access-Token missing from headers, we need this to query the Key Cloak API."))
    val keyCloakUrl = config.getOptional[String]("key-cloak.url")
      .getOrElse(throw new Exception("Missing key-cloak.url config value, we need this to query the Key Cloak API"))
    new KeyCloakClient(token, keyCloakUrl) with ProdSendAndReceive
  }

  private def timedEndPoint[A](name: String, maybeParams: Option[String])(eventualThing: Future[A]): Future[A] = {
    val startMillis = SDate.now().millisSinceEpoch
    eventualThing.foreach { _ =>
      val endMillis = SDate.now().millisSinceEpoch
      val millisTaken = endMillis - startMillis
      Metrics.timer(s"$name", millisTaken)
      log.info(s"$name${maybeParams.map(p => s" - $p").getOrElse("")} took ${millisTaken}ms")
    }
    eventualThing
  }

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
    Action.async { implicit request =>
      val start = SDate(startDay.toLong, europeLondonTimeZone)
      val end = start.addDays(sixMonthsDays)
      val userEmail = request.headers.get("X-Forwarded-Email").getOrElse("Unknown")
      ctrl.userService.selectUser(userEmail.trim).flatMap { userRow =>
        val minutesInSlot: Int = userRow.flatMap(_.staff_planning_interval_minutes).getOrElse(60)
        val numberOfSlots = minutesInADay / minutesInSlot
        val rowHeaders = Seq("") ++ (0 until numberOfSlots).map(qh => start.addMinutes(qh * minutesInSlot).toHoursAndMinutes)
        val staffingProvider = StaffRequirementExports.staffingForLocalDateProvider(ctrl.applicationService.terminalStaffMinutesProvider(terminal))
        val makeHourlyStaffing = StaffRequirementExports.toHourlyStaffing(staffingProvider, minutesInSlot)

        StaffRequirementExports
          .queuesProvider(ctrl.applicationService.terminalCrunchMinutesProvider(terminal))(start.toLocalDate, end.toLocalDate)
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
            val fileName = f"${airportConfig.portCode}-$terminal-forecast-export-${start.getFullYear}-${start.getMonth}%02d-${start.getDate}%02d"
            CsvFileStreaming.csvFileResult(fileName, csvData)
          }
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
        val queuesForDateRange = ctrl.applicationService.queuesForDateRangeAndTerminal(start.toLocalDate, end.toLocalDate, terminal).toSeq
        val queueNames = Queues.inOrder(queuesForDateRange).map(Queues.displayName)
        val rowHeaders = Seq("Date", "Total pax") ++ queueNames ++ Seq("Total workload")
        val makeHeadlines = StaffRequirementExports.toPassengerHeadlines(queuesForDateRange)

        StaffRequirementExports
          .queuesProvider(ctrl.applicationService.terminalCrunchMinutesProvider(terminal))(start.toLocalDate, end.toLocalDate)
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

  def startAndEndForDay(startDay: MillisSinceEpoch, numberOfDays: Int): (SDateLike, SDateLike) = {
    val startOfWeekMidnight = SDate(startDay).getLocalLastMidnight
    val endOfForecast = startOfWeekMidnight.addDays(numberOfDays)

    (startOfWeekMidnight, endOfForecast)
  }
}
