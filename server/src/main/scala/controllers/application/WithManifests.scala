package controllers.application

import actors.PartitionedPortStateActor.GetStateForDateRange
import akka.NotUsed
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import controllers.Application
import drt.shared.{ArrivalKey, ErrorResponse}
import manifests.passengers.PassengerInfo
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import play.api.mvc.{Action, AnyContent, Request}
import uk.gov.homeoffice.drt.auth.Roles.EnhancedApiView
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}
import upickle.default._

import java.util.Base64
import scala.concurrent.Future


trait WithManifests {
  self: Application =>

  private val manifestsProvider: (Long, Long) => Future[VoyageManifests] =
    (startMillis: Long, endMillis: Long) =>
      ctrl.manifestsRouterActor
        .ask(GetStateForDateRange(startMillis, endMillis))
        .mapTo[Source[VoyageManifests, NotUsed]]
        .flatMap(_.runFold(VoyageManifests.empty)(_ ++ _))

  private val manifestsForDay: UtcDate => Future[VoyageManifests] = WithManifests.manifestsForDay(manifestsProvider)
  private val manifestsForFlights: List[ArrivalKey] => Future[VoyageManifests] = WithManifests.manifestsForFlights(manifestsProvider)

  def getManifestSummariesForDay(utcDateString: String): Action[AnyContent] =
    authByRole(EnhancedApiView) {
      Action.async {
        UtcDate.parse(utcDateString) match {
          case Some(utcDate) => manifestsForDay(utcDate).map(manifests => Ok(WithManifests.manifestSummaries(manifests)))
          case _ => Future(BadRequest(write(ErrorResponse("Invalid scheduled date"))))
        }
      }
    }

  def getManifestSummariesForArrivals: Action[AnyContent] = authByRole(EnhancedApiView) {
    Action.async { request: Request[AnyContent] =>
      request.queryString.get("keys") match {
        case Some(strings) =>
          val arrivalKeys = strings.headOption
            .map(read[Set[ArrivalKey]](_))
            .getOrElse(Set())

          manifestsForFlights(arrivalKeys.toList)
            .map(manifests => Ok(WithManifests.manifestSummaries(manifests))
          )

        case _ =>
          Future(BadRequest(write(ErrorResponse("Invalid request body"))))
      }
    }
  }
}

object WithManifests {
  def manifestsForDay(manifestsProvider: (Long, Long) => Future[VoyageManifests])
                     (utcDate: UtcDate): Future[VoyageManifests] = {
    val date = SDate(utcDate)
    val midnightMillis = date.millisSinceEpoch
    val endOfTheDayMillis = date.addDays(1).addMinutes(-1).millisSinceEpoch

    manifestsProvider(midnightMillis, endOfTheDayMillis)
  }

  def manifestsForFlights(manifestsProvider: (Long, Long) => Future[VoyageManifests])
                         (arrivalKeys: List[ArrivalKey])
                         (implicit mat: Materializer): Future[VoyageManifests] = {
    val distinctArrivalDays = arrivalKeys.map(k => SDate(k.scheduled).toUtcDate).distinct
    val getManifests = manifestsForDay(manifestsProvider) _
    Source(distinctArrivalDays)
      .mapAsync(1)(getManifests)
      .map { manifests =>
        val relevantManifests = manifests.manifests.filter { m =>
          m.maybeKey.exists(arrivalKeys.contains)
        }
        VoyageManifests(relevantManifests)
      }
      .runFold(VoyageManifests.empty)(_ ++ _)
  }

  def manifestSummaries: VoyageManifests => String =
    (manifests: VoyageManifests) => {
      val summaries = manifests
        .manifests
        .map(PassengerInfo.manifestToFlightManifestSummary)
        .collect {
          case Some(pis) => pis
        }
      write(summaries)
    }
}
