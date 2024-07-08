package controllers.application

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.google.inject.Inject
import controllers.application.exports.CsvFileStreaming.{makeFileName, sourceToCsvResponse, sourceToJsonResponse}
import play.api.mvc._
import spray.json.enrichAny
import uk.gov.homeoffice.drt.auth.Roles.SuperAdmin
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.db.dao.{CapacityHourlyDao, PassengersHourlyDao}
import uk.gov.homeoffice.drt.jsonformats.PassengersSummaryFormat.JsonFormat
import uk.gov.homeoffice.drt.models.PassengersSummary
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{PortRegion, Queues}
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{DateRange, LocalDate, SDate}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}


class SummariesController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {
  def populatePassengersForDate(localDateStr: String): Action[AnyContent] = authByRole(SuperAdmin) {
    LocalDate.parse(localDateStr) match {
      case Some(localDate) =>
        Action.async(
          Source(Set(SDate(localDate).toUtcDate, SDate(localDate).addDays(1).addMinutes(-1).toUtcDate))
            .mapAsync(1)(ctrl.applicationService.populateLivePaxViewForDate)
            .run()
            .map(_ => Ok(s"Populated passengers for $localDate"))
        )
      case None =>
        Action(BadRequest(s"Invalid date format for $localDateStr. Expected YYYY-mm-dd"))
    }
  }
}
