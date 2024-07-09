package controllers.application

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.google.inject.Inject
import controllers.application.exports.CsvFileStreaming.sourceToCsvResponse
import drt.shared.CodeShares
import play.api.mvc._
import services.accuracy.ForecastAccuracyCalculator
import slickdb.ArrivalStatsRow
import uk.gov.homeoffice.drt.actor.PredictionModelActor
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.prediction.ModelAndFeatures
import uk.gov.homeoffice.drt.prediction.arrival.ArrivalModelAndFeatures
import uk.gov.homeoffice.drt.time.{DateRange, LocalDate, SDate}
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

      sourceToCsvResponse(stream, s"forecast-accuracy-${airportConfig.portCode.iata}.csv")
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
          val paxHeaders = comparisonHeaders(sortedModels, "pax")
          val loadHeaders = comparisonHeaders(sortedModels, "load")

          val headerRow = (Seq("Date", "Actual flights", "Forecast flights", "Unscheduled flights %", "Actual capacity", "Forecast capacity", "Capacity change %") ++ paxHeaders ++ loadHeaders).mkString(",") + "\n"

          streamDateRange(startDate, endDate, terminalName, getModels, modelNamesList.length)
            .prepend(Source(List(headerRow)))
        }

      sourceToCsvResponse(stream, s"forecast-model-comparison-${airportConfig.portCode.iata}-$terminalName.csv")
    }
  }

  private def comparisonHeaders(sortedModels: List[(String, ModelAndFeatures)], label: String): Seq[String] =
    Seq(s"Actual $label", s"Port forecast $label", s"Port forecast $label % diff") ++
      sortedModels.flatMap(nm => Seq(s"ML ${nm._1} $label,ML ${nm._1} $label % diff"))

  private def streamDateRange(startDate: LocalDate,
                              endDate: LocalDate,
                              terminalName: String,
                              getModels: Option[Long] => Future[PredictionModelActor.Models],
                              expectedModelCount: Int,
                             ): Source[String, NotUsed] = {
    val terminal = Terminal(terminalName)
    val terminalFlights: (LocalDate, Option[Long]) => Future[Seq[ApiFlightWithSplits]] = {
      (date, maybePit) =>
        ctrl.applicationService.flightsProvider.terminalDateScheduled(ctrl.materializer, ctrl.ec)(terminal)(date, maybePit)
          .map(_.filter(fws => !fws.apiFlight.Origin.isDomesticOrCta && !fws.apiFlight.isCancelled))
          .map(fs => CodeShares.uniqueArrivals(ctrl.paxFeedSourceOrder)(fs).toSeq)
    }
    val daysAhead = 3

    Source(DateRange(startDate, endDate))
      .mapAsync(1) { localDate =>
        getModels(Some(SDate(localDate).addDays(-daysAhead).millisSinceEpoch)).flatMap { models =>
          val sortedModels = models.models.toList.sortBy(_._1)
          val isNonHistoricDate = localDate >= ctrl.now().toLocalDate

          val futureMaybeModels: Future[Option[(Actuals, Forecast, List[ModelForecast])]] = if (!isNonHistoricDate) {
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
                    val actuals = actualsStats(localDate, isNonHistoricDate, actualArrivals)
                    if (localDate <= ctrl.now().toLocalDate) {
                      val pointInTime = SDate(localDate).addDays(-daysAhead)
                      terminalFlights(localDate, Option(pointInTime.millisSinceEpoch))
                        .map(forecastArrivals => (localDate, sortedModels, actuals, forecastArrivals))
                    } else
                      Future.successful((localDate, sortedModels, actuals, actualArrivals))
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
                csvRow(isNonHistoricDate, actuals, fcst, modelFcsts, expectedModelCount)
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
    val actRow = ArrivalStatsRow(
      portCode = airportConfig.portCode.iata,
      terminal = terminalName,
      date = localDate.toISOString,
      daysAhead = 0,
      dataType = "live",
      flights = actuals.flights,
      capacity = actuals.capacity,
      pax = actuals.pax,
      averageLoad = actuals.load,
      createdAt = ctrl.now().millisSinceEpoch)
    val fcstRow = ArrivalStatsRow(
      portCode = airportConfig.portCode.iata,
      terminal = terminalName,
      date = localDate.toISOString,
      daysAhead = daysAhead,
      dataType = "forecast",
      flights = fcst.flights,
      capacity = fcst.capacity,
      pax = fcst.pax,
      averageLoad = fcst.load,
      createdAt = ctrl.now().millisSinceEpoch)
    val modelFcstRows = modelFcsts.map(mf => ArrivalStatsRow(
      portCode = airportConfig.portCode.iata,
      terminal = terminalName,
      date = localDate.toISOString,
      daysAhead = daysAhead,
      dataType = mf.modelName,
      flights = mf.flights,
      capacity = fcst.capacity,
      pax = mf.pax,
      averageLoad = mf.load,
      createdAt = ctrl.now().millisSinceEpoch))
    ctrl.applicationService.arrivalStats.addOrUpdate(actRow).flatMap(
      _ => ctrl.applicationService.arrivalStats.addOrUpdate(fcstRow).flatMap(
        _ => Future.sequence(modelFcstRows.map(mf => ctrl.applicationService.arrivalStats.addOrUpdate(mf)))
      )
    )
  }

  private def actualsStats(localDate: LocalDate,
                           isNonHistoricDate: Boolean,
                           actualArrivals: Seq[ApiFlightWithSplits],
                          ): Actuals = {
    val actualFlights = if (isNonHistoricDate) 0 else actualArrivals.length
    val actPax = if (isNonHistoricDate) 0 else feedPaxTotal(localDate, actualArrivals, ctrl.paxFeedSourceOrder)
    val actLoadPct = if (isNonHistoricDate) 0d else feedLoadPctTotal(actualArrivals, ctrl.paxFeedSourceOrder)
    val capacity = actualArrivals.map(_.apiFlight.MaxPax.getOrElse(0)).sum
    Actuals(localDate, actualFlights, capacity, actPax, actLoadPct)
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
        val act = Actuals(
          date = localDate,
          flights = actual.flights,
          capacity = actual.capacity,
          pax = actual.pax,
          load = actual.averageLoad)
        val fcst = Forecast(localDate,
          flights = forecast.flights,
          capacity = forecast.capacity,
          pax = forecast.pax,
          load = forecast.averageLoad,
          daysAhead = daysAhead)
        val pred = modelForecast.map(mf => ModelForecast(localDate,
          flights = mf.flights,
          pax = mf.pax,
          load = mf.averageLoad,
          modelName = mf.dataType,
          daysAhead = daysAhead))
        (act, fcst, pred)
      }
    }

  private def toOptionalList[A](options: List[Option[A]]): Option[List[A]] =
    if (options.forall(_.isDefined)) Some(options.map(_.get)) else None

  private def parseLocalDate(startDateStr: String) =
    LocalDate
      .parse(startDateStr)
      .getOrElse(throw new Exception("Bad date format. Expected YYYY-mm-dd"))

  case class Actuals(date: LocalDate, flights: Int, capacity: Int, pax: Int, load: Double)

  case class Forecast(date: LocalDate, flights: Int, capacity: Int, pax: Int, load: Double, daysAhead: Int)

  case class ModelForecast(date: LocalDate, flights: Int, pax: Int, load: Double, modelName: String, daysAhead: Int)

  private def generateForecastStats(localDate: LocalDate,
                                    sortedModelsForDate: List[(String, ModelAndFeatures)],
                                    forecastArrivals: Seq[ApiFlightWithSplits]): (Forecast, Seq[ModelForecast]) = {
    val forecastFlights = forecastArrivals.length
    val forecastPax = feedPaxTotal(localDate, forecastArrivals, Seq(ForecastFeedSource))
    val forecastLoadPct = feedLoadPctTotal(forecastArrivals, Seq(ForecastFeedSource))
    val capacity = forecastArrivals.map(_.apiFlight.MaxPax.getOrElse(0)).sum
    val forecast = Forecast(localDate, forecastFlights, capacity, forecastPax, forecastLoadPct, 3)

    val modelForecasts: Seq[ModelForecast] = sortedModelsForDate.collect { case (modelName, model: ArrivalModelAndFeatures) =>
      val pax = predictedPaxTotal(model, localDate, forecastArrivals)
      val load = predictedLoadPctTotal(model, localDate, forecastArrivals)
      ModelForecast(localDate, forecastFlights, pax, load, modelName, 3)
    }

    (forecast, modelForecasts)
  }

  private def csvRow(isNonHistoricDate: Boolean, actuals: Actuals, forecast: Forecast, modelForecasts: Seq[ModelForecast], expectedModelCount: Int): String = {

    val forecastPaxDiff = if (isNonHistoricDate) "" else percentOrNa(forecast.pax, actuals.pax)
    val modelPax =
      if (modelForecasts.size == expectedModelCount) modelForecasts.map { mf =>
        val modelPaxDiff = if (isNonHistoricDate) "" else percentOrNa(mf.pax, actuals.pax)
        f"${mf.pax},$modelPaxDiff"
      }
      else Seq.fill(expectedModelCount)(",")

    val paxCells = Seq(actuals.pax.toString, forecast.pax.toString, forecastPaxDiff) ++ modelPax

    val forecastLoadDiff = if (isNonHistoricDate) "" else percentOrNa(forecast.load, actuals.load)
    val modelLoad =
      if (modelForecasts.size == expectedModelCount) modelForecasts.map { mf =>
        val modelLoadDiff = if (isNonHistoricDate) "" else percentOrNa(mf.load, actuals.load)
        f"${mf.load}%.2f,$modelLoadDiff"
      }
      else Seq.fill(expectedModelCount)(",")

    val loadCells = Seq(f"${actuals.load}%.2f", f"${forecast.load}%.2f", forecastLoadDiff) ++ modelLoad

    val unscheduledFlightsPct = if (isNonHistoricDate) "" else percentOrNa(actuals.flights, forecast.flights)
    val capacityChange = if (isNonHistoricDate) "" else percentOrNa(actuals.capacity, forecast.capacity)

    (Seq(actuals.date.toISOString, actuals.flights, forecast.flights, unscheduledFlightsPct, actuals.capacity, forecast.capacity, capacityChange) ++ paxCells ++ loadCells).mkString(",") + "\n"
  }

  private def percentOrNa(numerator: Int, denominator: Int): String = percentOrNa(numerator.toDouble, denominator.toDouble)

  private def percentOrNa(numerator: Double, denominator: Double): String =
    if (denominator > 0) f"${100 * (numerator - denominator) / denominator}%.2f"
    else "n/a"

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

  private def feedLoadPctTotal(arrivals: Seq[ApiFlightWithSplits], feedsPreference: Seq[FeedSource]): Double =
    arrivals
      .map { fws =>
        val pct = for {
          pcpPax <- fws.apiFlight.bestPcpPaxEstimate(feedsPreference)
          maxPax <- fws.apiFlight.MaxPax
        } yield (100 * pcpPax.toDouble / maxPax).round.toInt
        pct.getOrElse(0)
      }.sum.toDouble / arrivals.length

}
