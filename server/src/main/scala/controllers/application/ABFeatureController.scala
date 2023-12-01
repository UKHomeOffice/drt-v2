package controllers.application

import com.google.inject.Inject
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.ABFeature
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.db.ABFeatureRow
import upickle.default._

import java.sql.Timestamp
import java.time.Instant
import scala.concurrent.Future

class ABFeatureController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {
  implicit val rw: ReadWriter[ABFeature] = macroRW

  def getRandomABTest = {
    val random = scala.util.Random
    val randomInt = random.nextInt(100)
    if (randomInt < 50) {
      "A"
    } else {
      "B"
    }
  }

  def getABFeature(functionName: String): Action[AnyContent] = Action.async { implicit request =>
    val userEmail = request.headers.get("X-Auth-Email").getOrElse("Unknown")
    val abFeatureRowsF: Future[Seq[ABFeatureRow]] = ctrl.abFeatureService.getABFeaturesByEmailForFunction(userEmail, functionName)
    val abFeatures: Future[Seq[ABFeatureRow]] = abFeatureRowsF.flatMap { abFeatureRows =>
      abFeatureRows.size match {
        case 0 =>
          val row = ABFeatureRow(userEmail, functionName, new Timestamp(Instant.now().toEpochMilli), getRandomABTest)
          ctrl.abFeatureService.insertOrUpdate(row).map { _ =>
            Seq(row)
          }.recoverWith {
            case e => log.warning(s"Error while db insert for ab feature", e)
              Future.successful(Seq(row))
          }
        case _ => Future.successful(abFeatureRows)
      }
    }
    abFeatures.map(abFeature => Ok(write(abFeature.map(_.toABFeature))))
  }

}
