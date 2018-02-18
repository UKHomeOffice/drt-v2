package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.ml.regression.{LinearRegression, LinearRegressionModel}
import org.apache.spark.sql.functions.{col, concat_ws, expr}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.SplitsCalculator
import services.SplitsProvider.SplitProvider
import services.{CSVPassengerSplitsProvider, CsvPassengerSplitsReader, SDate, SplitsProvider}

import scala.collection.immutable
import scala.collection.immutable.IndexedSeq

case class FeatureSpec(columns: List[String], featurePrefix: String)

case class SplitPrediction(flight: String, scheduled: MilliDate, archetype: String, prediction: Double)

abstract class SplitsPredictorBase extends GraphStage[FlowShape[List[Arrival], List[(Arrival, Option[ApiSplits])]]]

class DummySplitsPredictor() extends SplitsPredictorBase {
  val in: Inlet[List[Arrival]] = Inlet[List[Arrival]]("SplitsPredictor.in")
  val out: Outlet[List[(Arrival, Option[ApiSplits])]] = Outlet[List[(Arrival, Option[ApiSplits])]]("SplitsPredictor.out")

  val shape: FlowShape[List[Arrival], List[(Arrival, Option[ApiSplits])]] = FlowShape.of(in, out)
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {

      setHandler(in, new InHandler {
        override def onPush(): Unit = {}
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {}
      })
    }
}

class SplitsPredictorStage(portCode: String, sparkSession: SparkSession, rawSplitsUrl: String) extends SplitsPredictorBase {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val in: Inlet[List[Arrival]] = Inlet[List[Arrival]]("SplitsPredictor.in")
  val out: Outlet[List[(Arrival, Option[ApiSplits])]] = Outlet[List[(Arrival, Option[ApiSplits])]]("SplitsPredictor.out")

  val shape: FlowShape[List[Arrival], List[(Arrival, Option[ApiSplits])]] = FlowShape.of(in, out)

  var modelledFlightCodes: Set[String] = Set()
  var maybeSplitsPredictor: Option[SplitsPredictor] = None
  var predictionsToPush: Option[List[(Arrival, Option[ApiSplits])]] = None

  val splitsView: DataFrame = sparkSession
    .read
    .option("header", "true")
    .option("inferSchema", "true")
    .csv(rawSplitsUrl)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      def tryPushing(): Unit = {
        predictionsToPush match {
          case None => log.info("No arrivals to push")
          case Some(toPush) if isAvailable(out) =>
            log.info(s"Pushing ${toPush.length} arrivals")
            push(out, toPush)
            predictionsToPush = None

          case Some(arrivalsToPush) =>
            log.info(s"Can't push ${arrivalsToPush.length} arrivals because outlet isn't available")
        }
      }

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          log.info(s"onPush")
          tryPushing()

          if (predictionsToPush.isEmpty) {
            val arrivals = grab(in)

            log.info(s"grabbed ${arrivals.length} arrivals for predictions")

            val flightCodesToModel = arrivals.map(_.IATA).toSet
            val unseenFlightCodes = flightCodesToModel -- modelledFlightCodes

            if (unseenFlightCodes.nonEmpty) {
              log.info(s"${unseenFlightCodes.size} unmodelled flight codes. Re-training")
              val updatedFlightCodesToModel = modelledFlightCodes ++ unseenFlightCodes
              maybeSplitsPredictor = Option(SplitsPredictor(sparkSession, portCode, updatedFlightCodesToModel, splitsView))
              modelledFlightCodes = updatedFlightCodesToModel
            } else {
              log.info(s"No new unmodelled flight codes so no need to re-train")
            }

            predictionsToPush = maybeSplitsPredictor.map(_.predictForArrivals(arrivals))
          } else {
            log.info(s"ignoring onPush() as we've not yet emitted our current arrivals")
          }

          if (!hasBeenPulled(in)) pull(in)
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          log.info(s"onPull")
          tryPushing()
          if (!hasBeenPulled(in)) pull(in)
        }
      })
    }
}

case class SplitsPredictor(sparkSession: SparkSession, portCode: String, flightCodes: Set[String], splitsView: DataFrame) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  val splitTypeToQueue = Map(
    "EeaMachineReadable" -> Queues.EeaDesk,
    "EeaNonMachineReadable" -> Queues.EeaDesk,
    "VisaNational" -> Queues.NonEeaDesk,
    "NonVisaNational" -> Queues.NonEeaDesk)

  val paxTypes: List[String] = splitTypeToQueue.keys.toList

  val featureSpecs = List(
    FeatureSpec(List("flight", "day"), "fd"),
    FeatureSpec(List("flight", "month"), "fm"),
    //              FeatureSpec(List("flight", "year"), "fy"),
    FeatureSpec(List("flight", "origin"), "fo"),
    FeatureSpec(List("day"), "d"),
    FeatureSpec(List("month"), "m"),
    //              FeatureSpec(List("year"), "y"),
    FeatureSpec(List("origin"), "o")
  )

  splitsView.createOrReplaceTempView("splits")

  val historicalSplitsProvider: SplitProvider = SplitsProvider.csvProvider

  val splitsCalculator = SplitsCalculator(portCode, historicalSplitsProvider, Set()) //airportConfig.defaultPaxSplits.splits.toSet),

  lazy val flightCodesToTrain: Seq[String] = flightsHavingTrainingExamples

  lazy val features: IndexedSeq[String] = extractFeatures

  lazy val paxTypeModels: Map[String, LinearRegressionModel] = paxTypes.map(paxType => (paxType, trainModel(paxType, features))).toMap

  def predictForArrivals(arrivals: List[Arrival]): List[(Arrival, Option[ApiSplits])] = {
    val arrivalsToPredict = arrivals.filter(a => flightCodesToTrain.contains(a.IATA))

    val flightSplitsPredictions: List[((String, Long), String, Double)] = paxTypes.flatMap(paxType => {
      log.info(s"Predicting $paxType values for ${arrivalsToPredict.length} arrivals")

      val predictionDf: DataFrame = predictionSetFromArrivals(arrivalsToPredict, features)

      val withPrediction = paxTypeModels(paxType).transform(predictionDf)

      val predictions: Seq[((String, Long), String, Double)] = withPrediction
        .collect.toSeq
        .map(row => {
          val flightCode = row.getAs[String]("flight")
          val scheduled = row.getAs[Long]("scheduled")
          val prediction = row.getAs[Double]("prediction")

          ((flightCode, scheduled), paxType, prediction)
        })

      log.info(s"Predicted ${predictions.length} $paxType values")
      predictions
    })

    log.info(s"Predicted ${flightSplitsPredictions.length} splits for ${arrivalsToPredict.length} arrivals")

    arrivalsWithPredictions(arrivals, flightSplitsPredictions)
  }

  def arrivalsWithPredictions(arrivals: List[Arrival], flightSplitsPredictions: List[((String, Long), String, Double)]): List[(Arrival, Option[ApiSplits])] = arrivals
    .map(a => {
      val arrivalSplits: immutable.Seq[(String, Double)] = flightSplitsPredictions
        .collect {
          case ((flightCode, scheduled), splitType, prediction) if flightCode == a.IATA && scheduled == a.Scheduled => (splitType, prediction)
        }
      val predictedSplits = arrivalSplits match {
        case splits if splits.isEmpty => None
        case splits =>
          val paxTypeAndQueueCounts = splits
            .map {
              case (splitType, prediction) =>
                ApiPaxTypeAndQueueCount(PaxType(s"""$splitType$$"""), splitTypeToQueue(splitType), prediction, None)
            }
            .toSet
          Option(ApiSplits(paxTypeAndQueueCounts, SplitSources.PredictedSplitsWithHistoricalEGateAndFTPercentages, None, Percentage))
      }
      (a, predictedSplits)
    })

  def predictionSetFromArrivals(arrivalsToPredict: List[Arrival], features: IndexedSeq[String]): DataFrame = {
    import sparkSession.implicits._

    val validationSetDf = arrivalsToPredict
      .map(arrival => {
        val sf: Seq[(Int, Double)] = featureSpecs
          .map { fs =>
            val featureValue = fs.columns.map(c => arrivalFeature(c, arrival)).mkString("-")
            val featureString = s"${fs.featurePrefix}$featureValue"
            val featureIdx = features.indexOf(featureString)
            //              log.info(s"$featureIdx: $featureString")
            (featureIdx, 1d)
          }
          .filterNot {
            case (-1, _) =>
              println(s"Couldn't find all features for $arrival")
              true
            case _ => false
          }

        val flightCode = arrivalFeature("flight", arrival)
        val scheduledMillis = arrival.Scheduled
        //          println(s"predicting $flightCode @ ${SDate(scheduled).toISOString()} with $sf")
        val sparseFeatures = Vectors.sparse(features.length, sf)

        (flightCode, scheduledMillis, sparseFeatures)
      })
      .toDS()
      .toDF("flight", "scheduled", "features")
    validationSetDf
  }

  def trainModel(labelColName: String, features: IndexedSeq[String]): LinearRegressionModel = {
    import sparkSession.implicits._

    val labelAndFeatures = col(labelColName) :: featureSpecs.map(fs => concat_ws("-", fs.columns.map(col): _*)) ++ List(col("flight"), col("scheduled"))
    val flightsWhereClause = whereClause(flightCodesToTrain)

    val trainingSet = splitsView
      .select(labelAndFeatures: _*)
      .where(expr(flightsWhereClause))
      .collect.toSeq
      .map(row => {
        val sf = featureSpecs
          .zipWithIndex
          .map {
            case (fs, idx) =>
              val featureString = s"${fs.featurePrefix}${row.getAs[String](idx + 1)}"
              val featureIdx = features.indexOf(featureString)
              (featureIdx, 1d)
          }

        (row.getAs[Double](0), Vectors.sparse(features.length, sf))
      })
      .filterNot(_._1.isNaN)
      .toDS
      .toDF("label", "features")

    val lr = new LinearRegression()

    lr.fit(trainingSet)
  }

  def flightsHavingTrainingExamples: Seq[String] = {
    val flightFilterWhereClause = whereClause(flightCodes.toSeq)

    val flightsWithTrainingExamples = splitsView
      .where(expr(flightFilterWhereClause))
      .groupBy(col("flight"))
      .count
      .withColumnRenamed("count", "numExamples")
      .filter("numExamples >= 10")
      .collect().toSeq
      .map(_.getAs[String]("flight"))

    log.info(s"${flightsWithTrainingExamples.length} out of ${flightCodes.size} flight codes have enough examples to train")

    flightsWithTrainingExamples
  }

  def arrivalFeature(feature: String, arrival: Arrival): String = feature match {
    case "flight" => arrival.IATA
    case "day" => SDate(arrival.Scheduled).getDayOfWeek().toString
    case "month" => SDate(arrival.Scheduled).getMonth().toString
    case "year" => SDate(arrival.Scheduled).getFullYear().toString
    case "origin" => arrival.Origin
  }

  def extractFeatures: IndexedSeq[String] = {
    val flightFilterWhereClause = whereClause(flightCodesToTrain)

    val extractedFeatures = featureSpecs.flatMap(fs => {
      splitsView
        .select(concat_ws("-", fs.columns.map(col): _*))
        .where(expr(flightFilterWhereClause))
        .rdd.distinct.collect
        .map(fs.featurePrefix + _.getAs[String](0))
    }).toIndexedSeq

    log.info(s"Found ${extractedFeatures.length} features from ${flightCodesToTrain.length} flight codes")

    extractedFeatures
  }

  def whereClause(flightCodes: Seq[String]): String = {
    s"""flight IN ("${flightCodes.mkString("\",\"")}") AND dest="$portCode" """
  }
}