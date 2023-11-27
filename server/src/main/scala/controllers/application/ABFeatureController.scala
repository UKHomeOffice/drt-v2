package controllers.application

import actors.DrtSystemInterface
import com.google.inject.Inject
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.ABFeature
import uk.gov.homeoffice.drt.db.ABFeatureRow
import upickle.default._

import scala.concurrent.Future

class ABFeatureController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {
  implicit val rw: ReadWriter[ABFeature] = macroRW

  def getABFeature(functionName: String): Action[AnyContent] = Action.async { implicit request =>
    val abFeatureRows: Future[Seq[ABFeatureRow]] = ctrl.abFeatureService.getABFeatureByFunctionName(functionName)
    abFeatureRows.map(abFeature => Ok(write(abFeature.map(_.toABFeature))))
  }

}
