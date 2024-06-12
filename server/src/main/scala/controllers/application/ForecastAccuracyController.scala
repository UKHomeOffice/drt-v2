package controllers.application

import actors.DateRange
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.google.inject.Inject
import controllers.application.exports.CsvFileStreaming.sourceToCsvResponse
import play.api.http.HttpEntity
import play.api.mvc._
import providers.FlightsProvider
import services.accuracy.ForecastAccuracyCalculator
import uk.gov.homeoffice.drt.actor.PredictionModelActor
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.prediction.ModelAndFeatures
import uk.gov.homeoffice.drt.prediction.arrival.ArrivalModelAndFeatures
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}
import upickle.default.write

import scala.concurrent.Future


class ForecastAccuracyController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def getForecastAccuracy(dateStr: String): Action[AnyContent] = auth {
    val daysToCalculate = List(1, 3, 7, 14, 30)

    Action.async { _ =>
      val maybeResponse = for {
        date <- LocalDate.parse(dateStr)
      } yield {
        ForecastAccuracyCalculator(date, daysToCalculate, ctrl.feedService.actualPaxNos, ctrl.feedService.forecastPaxNos, ctrl.now().toLocalDate)
      }
      maybeResponse match {
        case Some(eventualAccuracy) =>
          eventualAccuracy.map(acc => Ok(write(acc)))
        case None =>
          Future.successful(BadRequest("Invalid date"))
      }
    }
  }

  def forecastAccuracyExport(daysForComparison: Int, daysAhead: Int): Action[AnyContent] = auth {
    Action { _ =>
      val stream = ForecastAccuracyCalculator
        .predictionsVsLegacyForecast(daysForComparison, daysAhead, ctrl.feedService.actualArrivals, ctrl.feedService.forecastArrivals, ctrl.now().toLocalDate)
        .map {
          case (date, terminal, e) =>
            f"${date.toISOString},${terminal.toString},${maybeDoubleToPctString(e.predictionRmse)},${maybeDoubleToPctString(e.legacyRmse)},${maybeDoubleToPctString(e.predictionError)},${maybeDoubleToPctString(e.legacyError)}\n"
        }
        .prepend(Source(List("Date,Terminal,Prediction RMSE,Legacy RMSE,Prediction Error,Legacy Error\n")))

      sourceToCsvResponse(stream, "forecast-accuracy.csv")
    }
  }

  private def maybeDoubleToPctString(double: Option[Double]): String =
    double.map(d => f"${d * 100}%.3f").getOrElse("-")

  private def acceptHeader(request: Request[AnyContent]): String = {
    request.headers.get("Accept").getOrElse("application/json")
  }

  def forecastModelComparison(modelNames: String, terminalName: String, startDateStr: String, endDateStr: String): Action[AnyContent] = auth {
    Action.async { request =>
      val startDate = LocalDate
        .parse(startDateStr)
        .getOrElse(throw new Exception("Bad date format. Expected YYYY-mm-dd"))
      val endDate = LocalDate
        .parse(endDateStr)
        .getOrElse(throw new Exception("Bad date format. Expected YYYY-mm-dd"))
      val terminal = Terminal(terminalName)
      val terminalFlights = FlightsProvider(ctrl.actorService.flightsRouterActor).terminalLocalDate(ctrl.materializer)(terminal)
      val id = PredictionModelActor.Terminal(terminalName)
      val modelNamesList = modelNames.split(",").toList
      val getModelsForId: Option[Long] => PredictionModelActor.WithId => Future[PredictionModelActor.Models] =
        maybePointInTime => ctrl.feedService.flightModelPersistence.getModels(modelNamesList, maybePointInTime)

      val contentType = "text/csv"

      getModelsForId(None)(id).flatMap { models =>
        val sortedModels = models.models.toList.sortBy(_._1)
        val paxHeaders = Seq("Act", "Port Forecast", "Legacy DRT Forecast") ++ sortedModels.flatMap(nm => Seq(nm._1))
        val capHeaders = paxHeaders.map(_ + " Cap%")
        val headerRow = (Seq("Date") ++ paxHeaders ++ capHeaders).mkString(",") + "\n"
        Source(DateRange(startDate, endDate))
          .mapAsync(1) { localDate =>
            getModelsForId(Some(SDate(localDate).addDays(-3).millisSinceEpoch))(id).map { models =>
              log.info(s"Got ${models.models.size} models for $localDate")
              (localDate, models.models.toList.sortBy(_._1))
            }
          }
          .mapAsync(1) {
            case (localDate, sortedModelsForDate) =>
              terminalFlights(localDate, None)
                .map(actualArrivals => (localDate, sortedModelsForDate, actualArrivals))
          }
          .mapAsync(1) {
            case (localDate, sortedModelsForDate, actualArrivals) =>
              val pointInTime = SDate(localDate).addDays(-3)
              if (localDate <= ctrl.now().toLocalDate)
                terminalFlights(localDate, Option(pointInTime.millisSinceEpoch))
                  .map(forecastArrivals => (localDate, sortedModelsForDate, actualArrivals, Option(forecastArrivals)))
              else
                Future.successful((localDate, sortedModelsForDate, actualArrivals, None))
          }
          .map {
            case (localDate, sortedModelsForDate, actualArrivals, maybeForecastArrivals) =>
              generateCsvRow(localDate, sortedModelsForDate, actualArrivals, maybeForecastArrivals.getOrElse(actualArrivals))
          }
          .runWith(Sink.seq)
          .map { rows =>
            val content = headerRow + rows.mkString
            Result(
              header = ResponseHeader(200, Map("Content-Type" -> contentType)),
              body = HttpEntity.Strict(ByteString(content), Option(contentType))
            )
          }
      }
    }
  }

  private def generateCsvRow(localDate: LocalDate,
                             sortedModelsForDate: List[(String, ModelAndFeatures)],
                             actualArrivals: Seq[ApiFlightWithSplits],
                             forecastArrivals: Seq[ApiFlightWithSplits]): String = {
    val isNonHistoricDate = localDate >= ctrl.now().toLocalDate
    val validForecastArrivals = forecastArrivals.filter(a => !a.apiFlight.Origin.isDomesticOrCta && !a.apiFlight.isCancelled)
    val validActualArrivals = actualArrivals.filter(a => !a.apiFlight.Origin.isDomesticOrCta && !a.apiFlight.isCancelled)
    val actPax = if (isNonHistoricDate) 0 else feedPaxTotal(localDate, validActualArrivals, Seq(LiveFeedSource, ApiFeedSource))
    val actCapPct = if (isNonHistoricDate) 0d else feedCapPctTotal(localDate, validActualArrivals, Seq(LiveFeedSource, ApiFeedSource))
    val predPaxs = sortedModelsForDate.collect { case (_, model: ArrivalModelAndFeatures) => predictedPaxTotal(model, localDate, validForecastArrivals) }
    val predCapPct = sortedModelsForDate.collect { case (_, model: ArrivalModelAndFeatures) => predictedCapPctTotal(model, localDate, validForecastArrivals) }
    val forecastPax = feedPaxTotal(localDate, validForecastArrivals, Seq(ForecastFeedSource))
    val forecastCapPct = feedCapPctTotal(localDate, validForecastArrivals, Seq(ForecastFeedSource))
    val drtFcstPax = feedPaxTotal(localDate, validForecastArrivals, Seq(AclFeedSource, HistoricApiFeedSource))
    val drtFcstCapPct = feedCapPctTotal(localDate, validForecastArrivals, Seq(AclFeedSource, HistoricApiFeedSource))
    val paxCells = Seq(actPax.toString, forecastPax.toString, drtFcstPax.toString) ++ predPaxs.map(_.toString)
    val capCells = Seq(actCapPct, forecastCapPct, drtFcstCapPct) ++ predCapPct
    (Seq(localDate.toISOString) ++ paxCells ++ capCells.map(p => f"$p%.2f")).mkString(",") + "\n"
  }

  private val defaultCapPct = 80

  private def predictedPaxTotal(model: ArrivalModelAndFeatures, localDate: LocalDate, arrivals: Seq[ApiFlightWithSplits]): Int =
    arrivals.map { fws =>
      fws.apiFlight.MaxPax.map(mp => (model.prediction(fws.apiFlight).getOrElse(defaultCapPct).toDouble * mp / 100).round.toInt).getOrElse {
        log.warning(s"No max pax for ${fws.apiFlight.unique} on $localDate. Assuming freight, 0 pax")
        0
      }
    }.sum

  private def predictedCapPctTotal(model: ArrivalModelAndFeatures, localDate: LocalDate, arrivals: Seq[ApiFlightWithSplits]): Double =
    arrivals.map { fws =>
      model.prediction(fws.apiFlight).getOrElse {
        log.warning(s"No prediction for ${fws.apiFlight.unique} on $localDate. Using $defaultCapPct% for a default")
        defaultCapPct
      }
    }.sum.toDouble / arrivals.length

  private def feedPaxTotal(localDate: LocalDate, arrivals: Seq[ApiFlightWithSplits], feedsPreference: Seq[FeedSource]): Int =
    arrivals
      .map { fws =>
        fws.apiFlight.bestPcpPaxEstimate(feedsPreference).getOrElse {
          log.warning(s"No ${feedsPreference.map(_.name).mkString(", ")} for ${fws.apiFlight.unique} on $localDate. Using 0 for a default")
          0
        }
      }.sum

  private def feedCapPctTotal(localDate: LocalDate, arrivals: Seq[ApiFlightWithSplits], feedsPreference: Seq[FeedSource]): Double =
    arrivals
      .map { fws =>
        val pct = for {
          pcpPax <- fws.apiFlight.bestPcpPaxEstimate(feedsPreference)
          maxPax <- fws.apiFlight.MaxPax
        } yield (100 * pcpPax.toDouble / maxPax).round.toInt
        pct.getOrElse(0)
      }.sum.toDouble / arrivals.length
}
