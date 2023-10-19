package controllers.application

import actors.DrtSystemInterface
import akka.NotUsed
import akka.stream.scaladsl.{Sink, Source}
import com.google.inject.Inject
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import spray.json.DefaultJsonProtocol._
import spray.json.enrichAny
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}

import scala.concurrent.Future
import scala.language.postfixOps

class HealthCheckController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {
  def missingLiveApiData(windowMinutes: Int): Action[AnyContent] = Action.async { _ =>
    val end = ctrl.now()
    val start = end.addMinutes(-windowMinutes)
    missingApiPercentage(ctrl.flightsProvider.allTerminals, end, start).map(p => Ok(p.toJson.compactPrint))
  }

  private def missingApiPercentage(flights: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
                                   end: SDateLike,
                                   start: SDateLike): Future[Option[Double]] =
    flights(start.toUtcDate, end.toUtcDate)
      .map { case (_, flights) =>
        flights
          .filterNot(_.apiFlight.Origin.isDomesticOrCta)
          .filter { f =>
            val arrivalTime = f.apiFlight.bestArrivalTime(true)
            start.millisSinceEpoch <= arrivalTime && arrivalTime <= end.millisSinceEpoch
          }
      }
      .collect {
        case flights if flights.nonEmpty => flights
      }
      .map { flights =>
        val totalFlightsInWindow = flights.size
        val missingCount = flights.count { f =>
          !f.splits.exists(_.source == ApiSplitsWithHistoricalEGateAndFTPercentages)
        }
        println(s"\n\n** Missing $missingCount of $totalFlightsInWindow flights in window **\n\n")
        missingCount.toDouble / totalFlightsInWindow
      }
      .runWith(Sink.seq)
      .map(_.headOption)
}

