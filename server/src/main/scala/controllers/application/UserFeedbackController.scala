package controllers.application

import actors.DrtSystemInterface
import com.google.inject.Inject
import email.GovNotifyEmail
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.db.UserFeedbackRow
import uk.gov.homeoffice.drt.feedback.UserFeedback

import scala.concurrent.Future
import upickle.default.{macroRW, write}
import upickle.default._

import java.sql.Timestamp

class UserFeedbackController @Inject()(cc: ControllerComponents,
  ctrl: DrtSystemInterface,
  govNotifyEmail: GovNotifyEmail) extends AuthController(cc, ctrl) {
  implicit val rw: ReadWriter[UserFeedback] = macroRW

  def getUserFeedback: Action[AnyContent] = Action.async { implicit request =>
    val userEmail = request.headers.get("X-Auth-Email").getOrElse("Unknown")
    val userFeedbackRow: Future[Seq[UserFeedbackRow]] = ctrl.userFeedbackService.selectByEmail(userEmail)
    userFeedbackRow.map(userFeedbacks => Ok(write(userFeedbacks.map(_.toUserFeedback))))
      .recoverWith { case e =>
        log.error("Error getting user feedback: " + e.getMessage)
        Future(Ok("[]"))
      }
  }

  def closeBannerAction(feedbackType: String, aORbTest: String) = Action.async { implicit request =>
    val userEmail = request.headers.get("X-Auth-Email").getOrElse("Unknown")
    val result: Future[Int] = ctrl.userFeedbackService
      .insertOrUpdate(UserFeedbackRow(email = userEmail,
        actionedAt = new Timestamp(System.currentTimeMillis()),
        feedbackAt = None,
        closeBanner = true,
        feedbackType = Option(feedbackType),
        bfRole = "",
        drtQuality = "",
        drtLikes = None,
        drtImprovements = None,
        participationInterest = false,
        aOrBTest = Option(aORbTest)
      ))
    result.map(_ => Ok("Successfully closed banner"))
  }

}
