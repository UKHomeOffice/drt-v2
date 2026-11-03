package controllers.application

import com.google.inject.Inject
import drt.shared.{ AllocationPreview, ErrorResponse }
import play.api.mvc.{ Action, ControllerComponents }
import services.crunch.CalculationAborted
import services.crunch.deskrecs.AllocationPreviewService
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import upickle.default.{ read, write }

import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NonFatal

class QueueAllocationPreviewController @Inject() (cc: ControllerComponents, ctrl: DrtSystemInterface)
    extends AuthController(cc, ctrl) {
  def calculate: Action[String] = Action(parse.tolerantText(4096)).async { request =>
    val user = ctrl.getLoggedInUser(config, request.headers, request.session)
    if (!AllocationPreviewAccess.allowed(ctrl, user))
      Future.successful(Forbidden(write(ErrorResponse("Neutral view is not available"))))
    else Try(read[AllocationPreview.Request](request.body)).toOption
      .filter(input => Set("recommended", "available").contains(input.kind)) match {
      case None        => Future.successful(BadRequest(write(ErrorResponse("Invalid neutral request"))))
      case Some(input) => ctrl.applicationService.allocationPreviewService match {
          case None          => Future.successful(ServiceUnavailable(write(ErrorResponse("Neutral view is not available"))))
          case Some(service) => service.calculate(input).map(result => Ok(write(result))).recover {
              case AllocationPreviewService.Busy =>
                TooManyRequests(write(ErrorResponse("Neutral calculation is busy. Try again when it has finished.")))
              case AllocationPreviewService.Stopping =>
                ServiceUnavailable(write(ErrorResponse("Neutral calculation is unavailable")))
              case _: CalculationAborted =>
                log.warning("Neutral view calculation exceeded its execution budget or was stopped")
                GatewayTimeout(write(ErrorResponse("Neutral calculation timed out or was stopped. Try again.")))
              case _: IllegalArgumentException =>
                log.warning("Neutral view rejected invalid calculation inputs")
                BadRequest(write(ErrorResponse("Invalid neutral calculation inputs")))
              case NonFatal(error) =>
                log.error(s"Neutral view calculation failed (${error.getClass.getSimpleName})")
                InternalServerError(write(ErrorResponse("Neutral calculation failed. No saved results were changed.")))
            }
        }
    }
  }
}
