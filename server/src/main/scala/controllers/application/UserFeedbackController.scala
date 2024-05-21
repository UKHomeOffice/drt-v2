package controllers.application

import com.google.inject.Inject
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.db.UserFeedbackRow
import uk.gov.homeoffice.drt.feedback.UserFeedback
import upickle.default._
import scala.concurrent.Future

class UserFeedbackController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {
  implicit val rw: ReadWriter[UserFeedback] = macroRW

  def getUserFeedback: Action[AnyContent] = Action.async { implicit request =>
    val userEmail = request.headers.get("X-Forwarded-Email").getOrElse("Unknown")
    val userFeedbackRow: Future[Seq[UserFeedbackRow]] = ctrl.userFeedbackService.selectByEmail(userEmail)
    userFeedbackRow.map(userFeedbacks => Ok(write(userFeedbacks.map(_.toUserFeedback))))
      .recoverWith { case e =>
        log.error("Error getting user feedback: " + e.getMessage)
        Future(Ok("[]"))
      }
  }

}
