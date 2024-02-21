package controllers.application

import com.google.inject.Inject
import drt.shared._
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import services.{BankHolidayApiClient, OOHChecker}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone


class ContactDetailsController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def getContactDetails: Action[AnyContent] = Action { _ =>
    import upickle.default._

    Ok(write(ContactDetails(airportConfig.contactEmail, airportConfig.outOfHoursContactPhone)))
  }

  def getOOHStatus: Action[AnyContent] = Action.async { _ =>
    import upickle.default._

    val localTime = SDate.now(europeLondonTimeZone)

    OOHChecker(BankHolidayApiClient())
      .isOOH(localTime)
      .map { isOoh =>
        Ok(write(OutOfHoursStatus(localTime.toLocalDateTimeString, isOoh)))
      }
  }
}
