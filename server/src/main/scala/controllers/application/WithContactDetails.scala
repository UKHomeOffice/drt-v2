package controllers.application

import controllers.Application
import drt.shared._
import play.api.mvc.{Action, AnyContent}
import services.graphstages.Crunch
import services.{BankHolidayApiClient, OOHChecker, SDate}

import scala.concurrent.ExecutionContext.Implicits.global


trait WithContactDetails {
  self: Application =>

  def getContactDetails: Action[AnyContent] = Action { _ =>
    import upickle.default._

    Ok(write(ContactDetails(airportConfig.contactEmail, airportConfig.outOfHoursContactPhone)))
  }

  def getOOHStatus: Action[AnyContent] = Action.async { _ =>
    import upickle.default._

    val localTime = SDate.now(Crunch.europeLondonTimeZone)

    OOHChecker(BankHolidayApiClient()).isOOH(localTime).map { isOoh =>

      Ok(write(OutOfHoursStatus(localTime.toLocalDateTimeString(), isOoh)))
    }
  }
}
