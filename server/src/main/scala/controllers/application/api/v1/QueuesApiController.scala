package controllers.application.api.v1

import akka.stream.scaladsl.{Sink, Source}
import com.google.inject.Inject
import controllers.application.AuthController
import play.api.mvc._
import uk.gov.homeoffice.drt.auth.Roles.SuperAdmin
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.time.{DateRange, UtcDate}


class QueuesApiController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {
  def populateQueues(start: String, end: String): Action[AnyContent] =
    authByRole(SuperAdmin) {
      Action {
        val startDate = UtcDate.parse(start).getOrElse(throw new Exception("Invalid start date"))
        val endDate = UtcDate.parse(end).getOrElse(throw new Exception("Invalid end date"))
        if (startDate > endDate) {
          throw new Exception("Start date must be before end date")
        }
        Source(DateRange(startDate, endDate))
          .mapAsync(1) {
            date =>
              ctrl.applicationService.allTerminalsCrunchMinutesProvider(date, date).runForeach {
                case (_, flights) =>
                  ctrl.update15MinuteQueueSlotsLiveView(date, flights)
                  log.info(s"Updated queue slots for $date")
              }
          }
          .runWith(Sink.ignore)
        Ok("Queue slots populating")
      }
    }
}
