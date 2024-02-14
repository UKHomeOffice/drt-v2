package controllers.application

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
import uk.gov.homeoffice.drt.ports.{AclFeedSource, ForecastFeedSource}
import uk.gov.homeoffice.drt.prediction.arrival.ArrivalModelAndFeatures
import uk.gov.homeoffice.drt.prediction.persistence.Flight
import uk.gov.homeoffice.drt.time.LocalDate
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

  def forecastModelComparison(modelNames: String, terminalName: String, daysCount: Int): Action[AnyContent] = auth {
    Action.async { _ =>
      val terminal = Terminal(terminalName)
      val terminalFlights = FlightsProvider(ctrl.flightsRouterActor).terminalLocalDate(ctrl.materializer)(terminal)
      val id = PredictionModelActor.Terminal(terminalName)
      val modelNamesList = modelNames.split(",").toList
      val getModelsForId: PredictionModelActor.WithId => Future[PredictionModelActor.Models] = ctrl.flightModelPersistence.getModels(modelNamesList)

      getModelsForId(id).flatMap { models =>
        val sortedModels = models.models.toList.sortBy(_._1)
        val headerRow = (Seq("Date","Forecast") ++ sortedModels.map(_._1)).mkString(",") + "\n"
        Source(1 to daysCount)
          .mapAsync(1) { day =>
            val localDate = ctrl.now().addDays(day).toLocalDate
            terminalFlights(localDate)
              .map { arrivals =>
                val validArrivals = arrivals.filter(a => !a.apiFlight.Origin.isDomesticOrCta && !a.apiFlight.isCancelled)
                val predPaxs = sortedModels.collect { case (_, model: ArrivalModelAndFeatures) => predictedPaxTotal(model, localDate, validArrivals) }
                val forecastPax = forecastPaxTotal(localDate, validArrivals)
                (Seq(localDate, forecastPax.toString) ++ predPaxs.map(_.toString)).mkString(",") + "\n"
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

  private def predictedPaxTotal(model: ArrivalModelAndFeatures, localDate: LocalDate, arrivals: Seq[ApiFlightWithSplits]): Int =
    arrivals.map { fws =>
      fws.apiFlight.MaxPax.map(mp => (model.prediction(fws.apiFlight).getOrElse(80).toDouble * mp / 100).round.toInt).getOrElse {
        log.warning(s"No max pax for ${fws.apiFlight.unique} on $localDate. Assuming freight, 0 pax")
        0
      }
    }.sum

  private def forecastPaxTotal(localDate: LocalDate, arrivals: Seq[ApiFlightWithSplits]): Int =
    arrivals
      .map { fws =>
        fws.apiFlight.bestPcpPaxEstimate(Seq(ForecastFeedSource, AclFeedSource)).getOrElse {
          log.warning(s"No port or acl forecast for ${fws.apiFlight.unique} on $localDate. Using 175 for a default")
          175
        }
      }.sum
}
