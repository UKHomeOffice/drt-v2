package controllers.application

import actors.DrtSystemInterface
import com.google.inject.Inject
import drt.shared._
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import services.graphstages.Crunch
import services.{BankHolidayApiClient, OOHChecker}
import uk.gov.homeoffice.drt.time.SDate


class ContactDetailsController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def getContactDetails: Action[AnyContent] = Action { _ =>
    import upickle.default._

    Ok(write(ContactDetails(airportConfig.contactEmail, airportConfig.outOfHoursContactPhone)))
  }

  def getOOHStatus: Action[AnyContent] = Action.async { _ =>
    import upickle.default._

    val localTime = SDate.now(Crunch.europeLondonTimeZone)

    OOHChecker(BankHolidayApiClient())
      .isOOH(localTime)
      .map { isOoh =>
        Ok(write(OutOfHoursStatus(localTime.toLocalDateTimeString, isOoh)))
      }
  }
}
