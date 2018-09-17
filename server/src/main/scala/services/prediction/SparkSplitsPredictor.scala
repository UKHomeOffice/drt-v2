package services.prediction

import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.ml.regression.{LinearRegression, LinearRegressionModel}
import org.apache.spark.sql.functions.{col, concat_ws, expr}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

case class FeatureSpec(columns: List[String], featurePrefix: String)

sealed trait SplitsPredictorLike {
  def predictForArrivals(arrivals: Seq[Arrival]): Seq[(Arrival, Option[Splits])]
}

case class SparkSplitsPredictor(sparkSession: SparkSession, portCode: String, flightCodes: Set[String], splitsView: DataFrame)
  extends SplitsPredictorLike {
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
    FeatureSpec(List("flight", "origin"), "fo"),
    FeatureSpec(List("day"), "d"),
    FeatureSpec(List("month"), "m"),
    FeatureSpec(List("origin"), "o")
  )

  lazy val flightCodesToTrain: Seq[String] = flightsHavingTrainingExamples

  lazy val features: IndexedSeq[String] = extractFeatures

  lazy val paxTypeModels: Map[String, LinearRegressionModel] = paxTypes.map(paxType => (paxType, trainModel(paxType, features))).toMap

  def predictForArrivals(arrivals: Seq[Arrival]): Seq[(Arrival, Option[Splits])] = {
    val arrivalsToPredict = arrivals.filter(a => flightCodesToTrain.contains(a.IATA))

    val flightSplitsPredictions: List[((String, Long), String, Double)] = paxTypes.flatMap(paxType => {
      log.info(s"Predicting $paxType values for ${arrivalsToPredict.length} arrivals")

      val predictionDf: DataFrame = predictionDataFrameFromArrivals(arrivalsToPredict, features)

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

  def arrivalsWithPredictions(arrivals: Seq[Arrival], flightSplitsPredictions: Seq[((String, Long), String, Double)]): Seq[(Arrival, Option[Splits])] = arrivals
    .map(a => {
      val arrivalSplits: Seq[(String, Double)] = flightSplitsPredictions
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
          Option(Splits(paxTypeAndQueueCounts, SplitSources.PredictedSplitsWithHistoricalEGateAndFTPercentages, None, Percentage))
      }
      (a, predictedSplits)
    })

  def predictionDataFrameFromArrivals(arrivalsToPredict: Seq[Arrival], features: IndexedSeq[String]): DataFrame = {
    import sparkSession.implicits._

    val arrivalsPredictionDf = arrivalsToPredict
      .map(arrival => {
        val sf: Seq[(Int, Double)] = featureSpecs
          .map { fs =>
            val featureValue = fs.columns.map(c => arrivalFeature(c, arrival)).mkString("-")
            val featureString = s"${fs.featurePrefix}$featureValue"
            val featureIdx = features.indexOf(featureString)
            (featureIdx, 1d)
          }
          .filterNot {
            case (-1, _) =>
              log.debug(s"Couldn't find all features for ${arrival.IATA}")
              true
            case _ => false
          }

        val flightCode = arrivalFeature("flight", arrival)
        val scheduledMillis = arrival.Scheduled
        val sparseFeatures = Vectors.sparse(features.length, sf)

        (flightCode, scheduledMillis, sparseFeatures)
      })
      .toDS()
      .toDF("flight", "scheduled", "features")
    arrivalsPredictionDf
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