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
import uk.gov.homeoffice.drt.ports.{AclFeedSource, FeedSource, ForecastFeedSource, HistoricApiFeedSource, LiveFeedSource}
import uk.gov.homeoffice.drt.prediction.arrival.ArrivalModelAndFeatures
import uk.gov.homeoffice.drt.prediction.persistence.Flight
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
        ForecastAccuracyCalculator(date, daysToCalculate, ctrl.actualPaxNos, ctrl.forecastPaxNos, ctrl.now().toLocalDate)
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
        .predictionsVsLegacyForecast(daysForComparison, daysAhead, ctrl.actualArrivals, ctrl.forecastArrivals, ctrl.now().toLocalDate)
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
    Action.async { _ =>
      val startDate = LocalDate
        .parse(startDateStr)
        .getOrElse(throw new Exception("Bad date format. Expected YYYY-mm-dd"))
      val endDate = LocalDate
        .parse(endDateStr)
        .getOrElse(throw new Exception("Bad date format. Expected YYYY-mm-dd"))
      val terminal = Terminal(terminalName)
      val terminalFlights = FlightsProvider(ctrl.flightsRouterActor).terminalLocalDate(ctrl.materializer)(terminal)
      val id = PredictionModelActor.Terminal(terminalName)
      val modelNamesList = modelNames.split(",").toList
      val getModelsForId: PredictionModelActor.WithId => Future[PredictionModelActor.Models] = ctrl.flightModelPersistence.getModels(modelNamesList)

      getModelsForId(id).flatMap { models =>
        val sortedModels = models.models.toList.sortBy(_._1)
        val paxHeaders = Seq("Act", "Port Forecast", "DRT Forecast") ++ sortedModels.flatMap(nm => Seq(nm._1))
        val capHeaders = paxHeaders.map(_ + " Cap%")
        val headerRow = (Seq("Date") ++ paxHeaders ++ capHeaders).mkString(",") + "\n"
        Source(DateRange(startDate, endDate))
          .mapAsync(1) { localDate =>
            terminalFlights(localDate)
              .map { arrivals =>
                val validArrivals = arrivals.filter(a => !a.apiFlight.Origin.isDomesticOrCta && !a.apiFlight.isCancelled)
                val actPax = feedPaxTotal(localDate, validArrivals, Seq(LiveFeedSource))
                val actCapPct = feedCapPctTotal(validArrivals, Seq(LiveFeedSource))
                val predPaxs = sortedModels.collect { case (_, model: ArrivalModelAndFeatures) => predictedPaxTotal(model, localDate, validArrivals) }
                val predCapPct = sortedModels.collect { case (_, model: ArrivalModelAndFeatures) => predictedCapPctTotal(model, localDate, validArrivals) }
                val forecastPax = feedPaxTotal(localDate, validArrivals, Seq(ForecastFeedSource))
                val forecastCapPct = feedCapPctTotal(validArrivals, Seq(ForecastFeedSource))
                val drtFcstPax = feedPaxTotal(localDate, validArrivals, Seq(AclFeedSource, HistoricApiFeedSource))
                val drtFcstCapPct = feedCapPctTotal(validArrivals, Seq(AclFeedSource, HistoricApiFeedSource))
                val paxCells = Seq(actPax.toString, forecastPax.toString, drtFcstPax.toString) ++ predPaxs.map(_.toString)
                val capCells = Seq(actCapPct, forecastCapPct, drtFcstCapPct) ++ predCapPct
                (Seq(localDate.toISOString) ++ paxCells ++ capCells.map(p => f"$p%.2f")).mkString(",") + "\n"
              }
          }
          .runWith(Sink.seq)
          .map { rows =>
            val content = headerRow + rows.mkString
            Result(
              header = ResponseHeader(200, Map("Content-Type" -> "application/json")),
              body = HttpEntity.Strict(ByteString(content), Option("text/csv"))
            )
          }
      }
    }
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
          log.warning(s"No port or acl forecast for ${fws.apiFlight.unique} on $localDate. Using 0 for a default")
          0
        }
      }.sum

  private def feedCapPctTotal(arrivals: Seq[ApiFlightWithSplits], feedsPreference: Seq[FeedSource]): Double =
    arrivals
      .map { fws =>
        val pct = for {
          pcpPax <- fws.apiFlight.bestPcpPaxEstimate(feedsPreference)
          maxPax <- fws.apiFlight.MaxPax
        } yield (100 * pcpPax.toDouble / maxPax).round.toInt
        pct.getOrElse(0)
      }.sum.toDouble / arrivals.length
}
