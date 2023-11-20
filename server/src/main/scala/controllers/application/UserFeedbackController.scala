package controllers.application

import actors.DrtSystemInterface
import com.google.inject.Inject
import email.GovNotifyEmail
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.db.UserFeedbackRow
import scala.concurrent.Future

class UserFeedbackController @Inject()(cc: ControllerComponents,
                                       ctrl: DrtSystemInterface,
                                       govNotifyEmail: GovNotifyEmail) extends AuthController(cc, ctrl) {


  def getUserFeedback: Action[AnyContent] = Action.async { implicit request =>
    val userEmail = request.headers.get("X-Auth-Email").getOrElse("Unknown")
        val userFeedbackRow: Future[Seq[UserFeedbackRow]] = ctrl.userFeedbackService.selectByEmail(userEmail)
    //    userFeedbackRow.map(registered => Ok(write(registered)))
    Future(Ok("Not implemented"))
  }

}
