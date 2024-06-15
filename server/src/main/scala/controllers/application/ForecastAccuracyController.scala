package controllers.application

import actors.DateRange
import akka.NotUsed
import akka.stream.scaladsl.Source
import com.google.inject.Inject
import controllers.application.exports.CsvFileStreaming.sourceToCsvResponse
import play.api.mvc._
import providers.FlightsProvider
import services.accuracy.ForecastAccuracyCalculator
import slickdb.ArrivalStatsRow
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
    Action {
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

  def forecastModelComparison(modelNames: String, terminalName: String, startDateStr: String, endDateStr: String): Action[AnyContent] = auth {
    Action {
      val startDate = parseLocalDate(startDateStr)
      val endDate = parseLocalDate(endDateStr)

      val id = PredictionModelActor.Terminal(terminalName)
      val modelNamesList = modelNames.split(",").toList
      val getModels: Option[Long] => Future[PredictionModelActor.Models] =
        maybePointInTime => ctrl.feedService.flightModelPersistence.getModels(modelNamesList, maybePointInTime)(id)

      val stream = Source.future(getModels(None))
        .flatMapConcat { models =>
          val sortedModels = models.models.toList.sortBy(_._1)
          val paxHeaders = Seq("Actual pax", "Port forecast pax", "Port forecast pax % diff") ++
            sortedModels.flatMap(nm => Seq(s"ML ${nm._1} pax, ML ${nm._1} pax % diff"))
          val loadHeaders = Seq("Actual load", "Port forecast load", "Port forecast load % diff") ++
            sortedModels.flatMap(nm => Seq(s"ML ${nm._1} load", s"ML ${nm._1} load % diff"))

          val headerRow = (Seq("Date", "Actual flights", "Forecast flights", "Unscheduled flights %") ++ paxHeaders ++ loadHeaders).mkString(",") + "\n"

          streamDateRange(startDate, endDate, terminalName, getModels)
            .prepend(Source(List(headerRow)))
        }

      sourceToCsvResponse(stream, "forecast-model-comparison.csv")
    }
  }

  private def streamDateRange(startDate: LocalDate,
                              endDate: LocalDate,
                              terminalName: String,
                              getModels: Option[Long] => Future[PredictionModelActor.Models]): Source[String, NotUsed] = {
    val terminal = Terminal(terminalName)
    val terminalFlights = FlightsProvider(ctrl.actorService.flightsRouterActor).terminalLocalDate(ctrl.materializer)(terminal)
    val daysAhead = 3

    Source(DateRange(startDate, endDate))
      .mapAsync(1) { localDate =>
        getModels(Some(SDate(localDate).addDays(-daysAhead).millisSinceEpoch)).flatMap { models =>
          val sortedModels = models.models.toList.sortBy(_._1)
          val isNonHistoricDate = localDate >= ctrl.now().toLocalDate

          val futureMaybeModels = if (!isNonHistoricDate) {
            modelsFromCache(terminalName, daysAhead, localDate, sortedModels)
          } else {
            Future.successful(None)
          }

          futureMaybeModels
            .flatMap {
              case Some(models) =>
                log.info(s"Using cached stats for $localDate")
                Future.successful(models)
              case None =>
                log.info(s"Calculating stats for $localDate")
                terminalFlights(localDate, None)
                  .flatMap { actualArrivals =>
                    val (validActualArrivals, actuals) = validActualArrivalsAndStats(localDate, isNonHistoricDate, actualArrivals)

                    if (localDate <= ctrl.now().toLocalDate) {
                      val pointInTime = SDate(localDate).addDays(-daysAhead)
                      terminalFlights(localDate, Option(pointInTime.millisSinceEpoch))
                        .map(forecastArrivals => (localDate, sortedModels, actuals, forecastArrivals))
                    } else
                      Future.successful((localDate, sortedModels, actuals, validActualArrivals))
                  }
                  .map {
                    case (localDate, sortedModels, actuals, forecastArrivals) =>
                      val (fcst, modelFcsts) = generateForecastStats(localDate, sortedModels, forecastArrivals)

                      updateCachedStats(terminalName, daysAhead, localDate, actuals, fcst, modelFcsts)

                      (actuals, fcst, modelFcsts)
                  }
            }
            .map {
              case (actuals, fcst, modelFcsts) =>
                csvRow(isNonHistoricDate, actuals, fcst, modelFcsts)
            }
        }
      }
  }

  private def updateCachedStats(terminalName: String,
                                daysAhead: Int,
                                localDate: LocalDate,
                                actuals: Actuals,
                                fcst: Forecast,
                                modelFcsts: Seq[ModelForecast],
                               ): Future[Seq[Int]] = {
    val actRow = ArrivalStatsRow(airportConfig.portCode.iata, terminalName, localDate.toISOString, 0, "live", actuals.flights, actuals.pax, actuals.load, ctrl.now().millisSinceEpoch)
    val fcstRow = ArrivalStatsRow(airportConfig.portCode.iata, terminalName, localDate.toISOString, daysAhead, "forecast", fcst.flights, fcst.pax, fcst.load, ctrl.now().millisSinceEpoch)
    val modelFcstRows = modelFcsts.map(mf => ArrivalStatsRow(airportConfig.portCode.iata, terminalName, localDate.toISOString, daysAhead, mf.modelName, mf.flights, mf.pax, mf.load, ctrl.now().millisSinceEpoch))
    ctrl.applicationService.arrivalStats.addOrUpdate(actRow).flatMap(
      _ => ctrl.applicationService.arrivalStats.addOrUpdate(fcstRow).flatMap(
        _ => Future.sequence(modelFcstRows.map(mf => ctrl.applicationService.arrivalStats.addOrUpdate(mf)))
      )
    )
  }

  private def validActualArrivalsAndStats(localDate: LocalDate,
                                          isNonHistoricDate: Boolean,
                                          actualArrivals: Seq[ApiFlightWithSplits],
                                         ): (Seq[ApiFlightWithSplits], Actuals) = {
    val validActualArrivals = actualArrivals.filter(a => !a.apiFlight.Origin.isDomesticOrCta && !a.apiFlight.isCancelled)
    val actualFlights = if (isNonHistoricDate) 0 else validActualArrivals.length
    val actPax = if (isNonHistoricDate) 0 else feedPaxTotal(localDate, validActualArrivals, Seq(LiveFeedSource, ApiFeedSource))
    val actLoadPct = if (isNonHistoricDate) 0d else feedLoadPctTotal(localDate, validActualArrivals, Seq(LiveFeedSource, ApiFeedSource))
    val actuals = Actuals(localDate, actualFlights, actPax, actLoadPct)
    (validActualArrivals, actuals)
  }

  private def modelsFromCache(terminalName: String,
                              daysAhead: Int,
                              localDate: LocalDate,
                              sortedModels: List[(String, ModelAndFeatures)],
                             ): Future[Option[(Actuals, Forecast, List[ModelForecast])]] =
    for {
      actuals <- ctrl.applicationService.arrivalStats.get(terminalName, localDate.toISOString, 0, "live")
      forecasts <- ctrl.applicationService.arrivalStats.get(terminalName, localDate.toISOString, daysAhead, "forecast")
      modelForecasts <- Future.sequence(sortedModels.map { case (modelName, _) =>
        ctrl.applicationService.arrivalStats.get(terminalName, localDate.toISOString, daysAhead, modelName)
      })
    } yield {
      for {
        actual <- actuals
        forecast <- forecasts
        modelForecast <- toOptionalList(modelForecasts)
      } yield {
        val act = Actuals(localDate, actual.flights, actual.pax, actual.averageLoad)
        val fcst = Forecast(localDate, forecast.flights, forecast.pax, forecast.averageLoad, daysAhead)
        val pred = modelForecast.map(mf => ModelForecast(localDate, mf.flights, mf.pax, mf.averageLoad, mf.dataType, daysAhead))
        (act, fcst, pred)
      }
    }

  private def toOptionalList[A](options: List[Option[A]]) =
    if (options.forall(_.isDefined)) Some(options.map(_.get)) else None

  private def parseLocalDate(startDateStr: String) =
    LocalDate
      .parse(startDateStr)
      .getOrElse(throw new Exception("Bad date format. Expected YYYY-mm-dd"))

  case class Actuals(date: LocalDate, flights: Int, pax: Int, load: Double)

  case class Forecast(date: LocalDate, flights: Int, pax: Int, load: Double, daysAhead: Int)

  case class ModelForecast(date: LocalDate, flights: Int, pax: Int, load: Double, modelName: String, daysAhead: Int)

  private def generateForecastStats(localDate: LocalDate,
                                    sortedModelsForDate: List[(String, ModelAndFeatures)],
                                    forecastArrivals: Seq[ApiFlightWithSplits]): (Forecast, Seq[ModelForecast]) = {
    val validForecastArrivals = forecastArrivals.filter(a => !a.apiFlight.Origin.isDomesticOrCta && !a.apiFlight.isCancelled)
    val forecastFlights = validForecastArrivals.length
    val forecastPax = feedPaxTotal(localDate, validForecastArrivals, Seq(ForecastFeedSource))
    val forecastLoadPct = feedLoadPctTotal(localDate, validForecastArrivals, Seq(ForecastFeedSource))
    val forecast = Forecast(localDate, forecastFlights, forecastPax, forecastLoadPct, 3)

    val modelForecasts: Seq[ModelForecast] = sortedModelsForDate.collect { case (modelName, model: ArrivalModelAndFeatures) =>
      val pax = predictedPaxTotal(model, localDate, validForecastArrivals)
      val load = predictedLoadPctTotal(model, localDate, validForecastArrivals)
      ModelForecast(localDate, forecastFlights, pax, load, modelName, 3)
    }

    (forecast, modelForecasts)
  }

  private def csvRow(isNonHistoricDate: Boolean, actuals: Actuals, forecast: Forecast, modelForecasts: Seq[ModelForecast]): String = {

    val forecastPaxDiff = if (isNonHistoricDate) 0d else 100 * (forecast.pax - actuals.pax).toDouble / actuals.pax
    val paxCells = Seq(actuals.pax.toString, forecast.pax.toString, f"$forecastPaxDiff%.2f") ++
      modelForecasts.map { mf =>
        val modelPaxDiff = if (isNonHistoricDate) 0d else 100 * (mf.pax - actuals.pax).toDouble / actuals.pax
        f"${mf.pax},$modelPaxDiff%.2f"
      }

    val forecastLoadDiff = if (isNonHistoricDate) 0d else 100 * (forecast.load - actuals.load) / actuals.load
    val loadCells = Seq(f"${actuals.load}%.2f", f"${forecast.load}%.2f", f"$forecastLoadDiff%.2f") ++
      modelForecasts.map { mf =>
        val modelLoadDiff = if (isNonHistoricDate) 0d else 100 * (mf.load - actuals.load) / actuals.load
        f"${mf.load}%.2f,$modelLoadDiff%.2f"
      }

    val unscheduledFlightsPct = if (isNonHistoricDate) 0d else 100 * (forecast.flights - actuals.flights).toDouble / actuals.flights

    (Seq(actuals.date.toISOString, actuals.flights, forecast.flights, f"$unscheduledFlightsPct%.2f") ++ paxCells ++ loadCells).mkString(",") + "\n"
  }

  private val defaultLoadPct = 80

  private def predictedPaxTotal(model: ArrivalModelAndFeatures, localDate: LocalDate, arrivals: Seq[ApiFlightWithSplits]): Int =
    arrivals.map { fws =>
      fws.apiFlight.MaxPax.map(mp => (model.prediction(fws.apiFlight).getOrElse(defaultLoadPct).toDouble * mp / 100).round.toInt).getOrElse {
        log.warning(s"No max pax for ${fws.apiFlight.unique} on $localDate. Assuming freight, 0 pax")
        0
      }
    }.sum

  private def predictedLoadPctTotal(model: ArrivalModelAndFeatures, localDate: LocalDate, arrivals: Seq[ApiFlightWithSplits]): Double =
    arrivals.map { fws =>
      model.prediction(fws.apiFlight).getOrElse {
        log.warning(s"No prediction for ${fws.apiFlight.unique} on $localDate. Using $defaultLoadPct% for a default")
        defaultLoadPct
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

  private def feedLoadPctTotal(localDate: LocalDate, arrivals: Seq[ApiFlightWithSplits], feedsPreference: Seq[FeedSource]): Double =
    arrivals
      .map { fws =>
        val pct = for {
          pcpPax <- fws.apiFlight.bestPcpPaxEstimate(feedsPreference)
          maxPax <- fws.apiFlight.MaxPax
        } yield (100 * pcpPax.toDouble / maxPax).round.toInt
        pct.getOrElse(0)
      }.sum.toDouble / arrivals.length
}
