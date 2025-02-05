package controllers.application.api.v1

import org.apache.pekko.stream.scaladsl.{Sink, Source}
import com.google.inject.Inject
import controllers.application.AuthController
import play.api.mvc._
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.auth.Roles.SuperAdmin
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.time.{DateRange, UtcDate}


class FlightsApiController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {
  implicit val pfso: List[FeedSource] = ctrl.paxFeedSourceOrder

  def populateFlights(start: String, end: String): Action[AnyContent] =
    authByRole(SuperAdmin) {
      Action {
        val startDate = UtcDate.parse(start).getOrElse(throw new Exception("Invalid start date"))
        val endDate = UtcDate.parse(end).getOrElse(throw new Exception("Invalid end date"))
        if (startDate > endDate) {
          throw new Exception("Start date must be before end date")
        }
        Source(DateRange(startDate, endDate))
          .mapAsync(1) { date =>
            log.info(s"Populating flights for $date")
            ctrl.applicationService.flightsProvider.allTerminalsScheduledOn(date)
              .runWith(Sink.fold(Seq.empty[ApiFlightWithSplits])(_ ++ _))
              .flatMap { flights =>
                ctrl.updateFlightsLiveView(flights, Seq.empty)
                  .map(_ => log.info(s"Updated flights for $date"))
              }
          }
          .runWith(Sink.ignore)
        Ok("Flights populating")
      }
    }
}
