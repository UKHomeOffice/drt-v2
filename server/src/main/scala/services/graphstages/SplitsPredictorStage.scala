package services.graphstages

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared._
import drt.shared.SplitRatiosNs.SplitSources
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.ml.regression.LinearRegression
import org.apache.spark.sql.{Column, DataFrame, SparkSession}
import org.apache.spark.sql.functions.{col, concat_ws, expr}
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.SplitsCalculator
import services.{CSVPassengerSplitsProvider, CsvPassengerSplitsReader, SDate, SplitsProvider}
import services.SplitsProvider.SplitProvider

import scala.collection.immutable
import scala.collection.immutable.IndexedSeq

case class FeatureSpec(columns: List[String], whereClause: String, featurePrefix: String)

case class SplitPrediction(flight: String, scheduled: MilliDate, archetype: String, prediction: Double)

object Splits {
  def historic(portCode: String): SplitProvider = {
    val splitsFilePath = s"file:///home/rich/dev/${portCode.toLowerCase}-passenger-splits.csv"
    val splitsLines = CsvPassengerSplitsReader.flightPaxSplitsLinesFromPath(splitsFilePath)
    CSVPassengerSplitsProvider(splitsLines).splitRatioProvider
  }
}

class SplitsPredictorStage(splitsPredictor: SplitsPredictor) extends GraphStage[FlowShape[List[Arrival], List[(Arrival, Option[ApiSplits])]]] {
  val log = LoggerFactory.getLogger(getClass)

  val in = Inlet[List[Arrival]]("SplitsPredictor.in")
  val out = Outlet[List[(Arrival, Option[ApiSplits])]]("SplitsPredictor.out")

  val shape = FlowShape.of(in, out)

  var arrivalsWithPredictions: Option[List[(Arrival, Option[ApiSplits])]] = None

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      def tryPushing(): Unit = {
        arrivalsWithPredictions match {
          case None => log.info("No arrivals to push")
          case Some(toPush) if isAvailable(out) =>
            log.info(s"Pushing ${toPush.length} arrivals")
            push(out, toPush)
            arrivalsWithPredictions = None
          case Some(arrivalsToPush) =>
            log.info(s"Can't push ${arrivalsToPush.length} arrivals because outlet isn't available")
        }
      }

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          log.info(s"onPush")
          tryPushing()

          if (arrivalsWithPredictions.isEmpty) {
            val arrivals = grab(in)
            log.info(s"grabbed ${arrivals.length} arrivals for predictions")
            val predictions = splitsPredictor.predictForArrivals(arrivals)
            log.info(s"Predicted ${predictions.length} splits")
            arrivalsWithPredictions = Option(predictions)
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

object RunnablePredictor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply[X, Y](arrivalsSource: Source[List[Arrival], Y],
                  splitsPredictor: SplitsPredictorStage,
                  sink: Sink[List[(Arrival, Option[ApiSplits])], X]
                 ): RunnableGraph[(Y, NotUsed, X)] = {

    import GraphDSL.Implicits._

    RunnableGraph.fromGraph(GraphDSL.create(arrivalsSource, splitsPredictor, sink)((_, _, _)) { implicit builder =>
      (arrivals, predictor, out) =>
        arrivals ~> predictor ~> out
        ClosedShape
    })
  }
}

case class SplitsPredictor(sparkSession: SparkSession, portCode: String) {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val splitsView: DataFrame = sparkSession
    .read
    .option("header", "true")
    .option("inferSchema", "true")
    //        .csv("/tmp/all-splits-from-api.csv")
    .csv("/home/rich/dev/all-splits-from-api-from-2016-12.csv")

  splitsView.createOrReplaceTempView("splits")
  //  splitsView.printSchema()

  val historicalSplitsProvider: SplitProvider = SplitsProvider.csvProvider

  val splitsCalculator = SplitsCalculator(portCode, historicalSplitsProvider, Set()) //airportConfig.defaultPaxSplits.splits.toSet),

  def predictForArrivals(arrivals: List[Arrival]): List[(Arrival, Option[ApiSplits])] = {

    val flightCodesToTrain = flightsHavingTrainingExamples(arrivals)
    log.info(s"flightCodesToTrain: $flightCodesToTrain")
    val arrivalsToPredict = arrivals.filter(a => flightCodesToTrain.contains(a.IATA))

    val (features, featureSpecs) = extractFeatures(arrivalsToPredict)
    log.info(s"Using ${features.length} features")

    val whereClause = s"""flight IN ("${flightCodesToTrain.mkString("\",\"")}") AND dest="$portCode" """

    val splitTypeToQueue = Map(
      "EeaMachineReadable" -> Queues.EeaDesk,
      "EeaNonMachineReadable" -> Queues.EeaDesk,
      "VisaNational" -> Queues.NonEeaDesk,
      "NonVisaNational" -> Queues.NonEeaDesk)
    val splitTypes = splitTypeToQueue.keys.toList

    val flightSplitsPredictions: List[((String, Long), String, Double)] = splitTypes.flatMap(label => {
      log.info(s"looking at $label")

      val labelAndFeatures = col(label) :: featureSpecs.map(fs => concat_ws("-", fs.columns.map(col): _*)) ++ List(col("flight"), col("scheduled"))

      val lrModel = trainModel(whereClause, labelAndFeatures, featureSpecs, features)

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
        .cache()

      log.info(s"${validationSetDf.count} validation rows after excluding missing historic splits")

      val withPrediction = lrModel.transform(validationSetDf)

      val predictions: Seq[((String, Long), String, Double)] = withPrediction
        .collect.toSeq
        .map(row => {
          val flightCode = row.getAs[String]("flight")
          val scheduled = row.getAs[Long]("scheduled")
          val prediction = row.getAs[Double]("prediction")

          //          println(f"$label: $flightCode @ ${SDate(scheduled.getTime).toLocalDateTimeString()} $y%.2f / $prediction%.2f / $historic%.2f - winner: $winner")

          ((flightCode, scheduled), label, prediction)
        })

      log.info(s"trained $label")
      predictions
    })

    arrivals
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
            val withEgatesAndFt = splitsCalculator.addEgatesAndFastTrack(a, paxTypeAndQueueCounts)
            Option(ApiSplits(withEgatesAndFt, SplitSources.PredictedSplitsWithHistoricalEGateAndFTPercentages, None, Percentage))
        }
        (a, predictedSplits)
      })
  }

  private def trainModel(whereClause: String, labelAndFeatures: List[Column], featureSpecs: List[FeatureSpec], features: IndexedSeq[String]) = {
    import sparkSession.implicits._

    val trainingSet = splitsView
      .select(labelAndFeatures: _*)
      .where(expr(whereClause))
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

  def flightsHavingTrainingExamples(arrivals: List[Arrival]): Seq[String] = {
    val flightFilterWhereClause = s"""flight IN ("${arrivals.map(_.IATA).mkString("\",\"")}") AND dest="$portCode" """
    splitsView
      .where(expr(flightFilterWhereClause))
      .groupBy(col("flight"))
      .count
      .withColumnRenamed("count", "numExamples")
      .filter("numExamples >= 10")
      .collect().toSeq
      .map(_.getAs[String]("flight"))
  }

  def arrivalFeature(feature: String, arrival: Arrival): String = feature match {
    case "flight" => arrival.IATA
    case "day" => SDate(arrival.Scheduled).getDayOfWeek().toString
    case "month" => SDate(arrival.Scheduled).getMonth().toString
    case "year" => SDate(arrival.Scheduled).getFullYear().toString
    case "origin" => arrival.Origin
  }

  def extractFeatures(arrivals: List[Arrival]): (IndexedSeq[String], List[FeatureSpec]) = {
    val flightFilterWhereClause = s"""flight IN ("${arrivals.map(_.IATA).mkString("\",\"")}") AND dest="$portCode" """
    val featureSpecs = List(
      FeatureSpec(List("flight", "day"), flightFilterWhereClause, "fd"),
      FeatureSpec(List("flight", "month"), flightFilterWhereClause, "fm"),
      //              FeatureSpec(List("flight", "year"), flightFilterWhereClause, "fy"),
      FeatureSpec(List("flight", "origin"), flightFilterWhereClause, "fo"),
      FeatureSpec(List("day"), flightFilterWhereClause, "d"),
      FeatureSpec(List("month"), flightFilterWhereClause, "m"),
      //              FeatureSpec(List("year"), flightFilterWhereClause, "y"),
      FeatureSpec(List("origin"), flightFilterWhereClause, "o")
    )

    val features = featureSpecs.flatMap(fs => {
      splitsView
        .select(concat_ws("-", fs.columns.map(col): _*))
        .where(expr(fs.whereClause))
        .rdd.distinct.collect
        .map(fs.featurePrefix + _.getAs[String](0))
    }).toIndexedSeq

    (features, featureSpecs)
  }
}