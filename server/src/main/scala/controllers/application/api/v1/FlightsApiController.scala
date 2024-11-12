package controllers.application.api.v1

import actors.PartitionedPortStateActor.GetFlightsForTerminals
import akka.NotUsed
import akka.pattern.ask
import akka.stream.scaladsl.{Sink, Source}
import com.google.inject.Inject
import controllers.application.AuthController
import play.api.mvc._
import services.api.v1.FlightExport
import services.api.v1.serialisation.FlightApiJsonProtocol
import spray.json.enrichAny
import uk.gov.homeoffice.drt.arrivals.{Arrival, FlightsWithSplits}
import uk.gov.homeoffice.drt.auth.Roles.{ApiFlightAccess, ApiQueueAccess}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{DateRange, SDate, SDateLike, UtcDate}

import scala.concurrent.Future
import scala.util.Try


class FlightsApiController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) with FlightApiJsonProtocol {
  implicit val pfso: List[FeedSource] = ctrl.paxFeedSourceOrder

  private val flightTotalsForGranularity: (SDateLike, SDateLike, Terminal) => Future[Seq[Arrival]] =
    (start, end, terminal) => {
      val request = GetFlightsForTerminals(start.millisSinceEpoch, end.millisSinceEpoch, Seq(terminal))

      ctrl.actorService.flightsRouterActor.ask(request).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
        .flatMap(_
          .map { case (_, FlightsWithSplits(flights)) =>
            flights.values
              .filter { fws =>
                val startTime = Try(fws.apiFlight.pcpRange(pfso).min).getOrElse(fws.apiFlight.Scheduled)
                val endTime = Try(fws.apiFlight.pcpRange(pfso).max).getOrElse(fws.apiFlight.Scheduled)
                startTime >= start.millisSinceEpoch && endTime <= end.millisSinceEpoch
              }
              .map(_.apiFlight).toSeq
              .sortBy(a => Try(a.pcpRange(pfso).min).getOrElse(a.Scheduled))
          }
          .runWith(Sink.fold(Seq[Arrival]())(_ ++ _))
        )
    }

  private val flightExport: (SDateLike, SDateLike) => Future[FlightExport.PortFlightsJson] =
    FlightExport(flightTotalsForGranularity, ctrl.airportConfig.terminals, ctrl.airportConfig.portCode)

  def flights(): Action[AnyContent] =
    authByRole(ApiFlightAccess) {
      Action.async {
        request =>
          val start = parseOptionalEndDate(request.getQueryString("start"), SDate.now())
          val end = parseOptionalEndDate(request.getQueryString("end"), SDate.now())

          if (start > end) {
            throw new Exception("Start date must be before end date")
          }

          flightExport(start, end).map(r => Ok(r.toJson.compactPrint))
      }
    }

  def populateFlights(start: String, end: String): Action[AnyContent] =
    authByRole(ApiQueueAccess) {
      Action {
        val startDate = UtcDate.parse(start).getOrElse(throw new Exception("Invalid start date"))
        val endDate = UtcDate.parse(end).getOrElse(throw new Exception("Invalid end date"))
        if (startDate > endDate) {
          throw new Exception("Start date must be before end date")
        }
        Source(DateRange(startDate, endDate))
          .mapAsync(1) { date =>
            ctrl.applicationService.flightsProvider.allTerminalsScheduledOn(date).runForeach { flights =>
              ctrl.updateFlightsLiveView(flights, Seq.empty)
              log.info(s"Updated flights for $date")
            }
          }
          .runWith(Sink.ignore)
        Ok("Flights populating")
      }
    }


  private def parseOptionalEndDate(maybeString: Option[String], default: SDateLike): SDateLike =
    maybeString match {
      case None => default
      case Some(dateStr) => SDate(dateStr)
    }
}
