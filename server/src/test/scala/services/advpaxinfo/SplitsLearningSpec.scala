package services.advpaxinfo

import java.sql.Timestamp

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream._
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import controllers.ArrivalGenerator
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.ml.regression.LinearRegression
import org.apache.spark.sql.{Column, SparkSession}
import org.apache.spark.sql.functions.{col, concat_ws, expr}
import org.slf4j.{Logger, LoggerFactory}
import org.specs2.mutable.Specification
import passengersplits.core.SplitsCalculator
import services.SplitsProvider.SplitProvider
import services.{CSVPassengerSplitsProvider, CsvPassengerSplitsReader, SDate, SplitsProvider}

import scala.collection.immutable
import scala.collection.immutable.IndexedSeq
import scala.concurrent.duration._

case class FeatureSpec(columns: List[String], whereClause: String, featurePrefix: String)

case class SplitPrediction(flight: String, scheduled: MilliDate, archetype: String, prediction: Double)

object Splits {
  def historic(portCode: String): SplitProvider = {
    val splitsFilePath = s"file:///home/rich/dev/${portCode.toLowerCase}-passenger-splits-old.csv"
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
            log.info(s"grabbed ${arrivals.length} arrivals")
            arrivalsWithPredictions = Option(splitsPredictor.predictForArrivals(arrivals))

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
  val splitsView = sparkSession
    .read
    .option("header", "true")
    .option("inferSchema", "true")
    //        .csv("/tmp/all-splits-from-api.csv")
    .csv("/home/rich/dev/all-splits-from-api-from-2016-12.csv")

  splitsView.createOrReplaceTempView("splits")
  //  splitsView.printSchema()

  val historicalSplitsProvider = SplitsProvider.csvProvider

  val splitsCalculator = SplitsCalculator(portCode, historicalSplitsProvider, Set()) //airportConfig.defaultPaxSplits.splits.toSet),

  def predictForArrivals(arrivals: List[Arrival]): List[(Arrival, Option[ApiSplits])] = {

    val flightCodesToTrain = flightsHavingTrainingExamples(arrivals)
    val arrivalsToPredict = arrivals.filter(a => flightCodesToTrain.contains(a.IATA))

    val (features, featureSpecs) = extractFeatures(arrivalsToPredict)
    println(s"Using ${features.length} features")

    val whereClause = s"""flight IN ("${flightCodesToTrain.mkString("\",\"")}") AND dest="$portCode" """

    val splitTypeToQueue = Map(
      "EeaMachineReadable" -> Queues.EeaDesk,
      "EeaNonMachineReadable" -> Queues.EeaDesk,
      "VisaNational" -> Queues.NonEeaDesk,
      "NonVisaNational" -> Queues.NonEeaDesk)
    val splitTypes = splitTypeToQueue.keys.toList

    val flightSplitsPredictions: List[((String, Long), String, Double)] = splitTypes.flatMap(label => {
      println(s"looking at $label")

      val labelAndFeatures = col(label) :: featureSpecs.map(fs => concat_ws("-", fs.columns.map(col): _*)) ++ List(col("flight"), col("scheduled"))

      val lrModel = trainModel(whereClause, labelAndFeatures, featureSpecs, features)

      import sparkSession.implicits._

      val validationSetDf = arrivalsToPredict
        .map(arrival => {
          val sf = featureSpecs
            .map { fs =>
              val featureValue = fs.columns.map(c => arrivalFeature(c, arrival)).mkString("-")
              val featureString = s"${fs.featurePrefix}$featureValue"
              val featureIdx = features.indexOf(featureString)
              println(s"$featureIdx: $featureString")
              (featureIdx, 1d)
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

      println(s"${validationSetDf.count} validation rows after excluding missing historic splits")

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

      println(s"trained $label")
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

class SplitsLearningSpec extends Specification {
  val rawZipFilesPath: String = ConfigFactory.load.getString("dq.raw_zip_files_path")
  "woop" >> {
    implicit val actorSystem: ActorSystem = ActorSystem("splits")
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    val sparkSession: SparkSession = SparkSession
      .builder
      .appName("Simple Application")
      .config("spark.master", "local")
      .getOrCreate()
    val splitsPredictor = SplitsPredictor(sparkSession, "LHR")

    val arrivals = List(ArrivalGenerator.apiFlight(iata = "BA0565", schDt = "2018-02-13", origin = "LIN"))
    val sourceUnderTest: Source[List[Arrival], Cancellable] = Source.tick(5 seconds, 5 minute, arrivals)

    val probe = TestProbe()

    val sink = Sink.actorRef(probe.ref, "completed")
    val predictorStage = new SplitsPredictorStage(splitsPredictor)
    val (runnable, _, _) = RunnablePredictor(sourceUnderTest, predictorStage, sink).run

    probe.fishForMessage(5 minutes) {
      case (arrival, splits) :: Nil =>
        println(s"Got a message: $arrival, $splits")
        true
      case _ => false
    }
//    runnable.cancel()
    true
  }

  "I can read a splits csv into spark" >> {
    skipped("yeah")
    import org.apache.spark.sql.SparkSession

    val sparkSession: SparkSession = SparkSession
      .builder
      .appName("Simple Application")
      .config("spark.master", "local")
      .getOrCreate()

    import sparkSession.implicits._


    val stuff = sparkSession
      .read
      .option("header", "true")
      .option("inferSchema", "true")
      //        .csv("/tmp/all-splits-from-api.csv")
      .csv("/home/rich/dev/all-splits-from-api-from-2016-12.csv")

    stuff.createOrReplaceTempView("splits")

    stuff.printSchema()

    import org.apache.spark.sql.functions._

    val allFlights = Seq(/*"LHR", "STN",*/ "MAN")

    //    val trainingStartDate = "2017-10-01"
    val validationStartDate = "2017-12-20"
    val validationEndDate = "2017-12-30"

    allFlights.foreach { portCode =>
      val historicSplitsProvider = Splits.historic(portCode)

      val flights = stuff
        .select(col("flight"))
        .where(col("scheduled") between(validationStartDate, validationEndDate))
        .where(col("dest") === portCode)
        .distinct()
        .collect().toSeq
        .map(_.getAs[String]("flight"))

      println(s"$portCode flights between $validationStartDate - $validationEndDate: $flights")

      val flightFilterWhereClause = s"""flight IN ("${flights.mkString("\",\"")}") AND dest="$portCode" """
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
        stuff
          .select(concat_ws("-", fs.columns.map(col): _*))
          .where(expr(fs.whereClause))
          .rdd.distinct.collect
          .map(fs.featurePrefix + _.getAs[String](0))
      }).toIndexedSeq
      println(s"Using ${features.length} features")


      val flightsToTrain = stuff
        .where(expr(flightFilterWhereClause))
        .where(col("scheduled") < validationStartDate)
        .groupBy(col("flight"))
        .count
        .withColumnRenamed("count", "numExamples")
        .filter("numExamples >= 10")
        .collect().toSeq
        .map(_.getAs[String]("flight"))

      val whereClause = s"""flight IN ("${flightsToTrain.mkString("\",\"")}") AND dest="$portCode" """

      println(s"$portCode")
      List("EeaMachineReadable", "EeaNonMachineReadable", "VisaNational", "NonVisaNational").map(label => {
        val labelAndFeatures = col(label) :: featureSpecs.map(fs => concat_ws("-", fs.columns.map(col): _*)) ++ List(col("flight"), col("scheduled"))

        val trainingSet = stuff
          .select(labelAndFeatures: _*)
          .where(expr(whereClause))
          .where(col("scheduled") < validationStartDate)
          //        .where(col("scheduled") between("2017-07-01", validationStartDate))
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
        val lrModel = lr.fit(trainingSet)
        val trainingSummary = lrModel.summary

        val validationSet = stuff
          .select(labelAndFeatures: _*)
          .where(expr(whereClause))
          .where(col("scheduled") between(validationStartDate, validationEndDate))
          .collect.toSeq

        println(s"$label: ${validationSet.length} predictions")

        val validationSetDf = validationSet
          .map(row => {
            val sf = featureSpecs
              .zipWithIndex
              .map {
                case (fs, idx) =>
                  val featureString = s"${fs.featurePrefix}${row.getAs[String](idx + 1)}"
                  val featureIdx = features.indexOf(featureString)
                  (featureIdx, 1d)
              }

            val flightCode = row.getAs[String]("flight")
            val scheduledTs = row.getAs[Timestamp]("scheduled")
            val scheduled = MilliDate(scheduledTs.getTime)
            //          println(s"predicting $flightCode @ ${SDate(scheduled).toISOString()} with $sf")
            val y = row.getAs[Double](label)
            val sparseFeatures = Vectors.sparse(features.length, sf)

            val historic: Option[Double] = historicSplitsProvider(flightCode, scheduled).map(sr => {
              sr.splits.filter(_.paxType.passengerType.cleanName == label).map(_.ratio).sum
            })

            (flightCode, scheduledTs, sparseFeatures, y, historic)
          })
          .collect { case (f, s, ft, y, Some(h)) =>
            (f, s, ft, y, h)
          }
          .toDS()
          .toDF("flight", "scheduled", "features", "label", "historic")
          .cache()

        println(s"${validationSetDf.count} validation rows after excluding missing historic splits")

        val summary = lrModel.evaluate(validationSetDf)
        val withPrediction = lrModel.transform(validationSetDf)

        val flightAndPred = withPrediction
          .collect.toSeq
          .map(row => {
            val flightCode = row.getAs[String]("flight")
            val scheduled = row.getAs[Timestamp]("scheduled")

            val historic = row.getAs[Double]("historic")

            val y = row.getAs[Double]("label")
            val p = row.getAs[Double]("prediction")

            val winner = (Math.abs(y - p), Math.abs(y - historic)) match {
              case (diffP, diffH) if Math.abs(diffP - diffH) < 0.01 => "n/a"
              case (diffP, diffH) if diffP <= diffH => s"P" //: ${diffH - diffP}"
              case (diffP, diffH) if diffP > diffH => s"H" //: ${diffP - diffH}"
              case _ => "n/a"
            }

            //          println(f"$label: $flightCode @ ${SDate(scheduled.getTime).toLocalDateTimeString()} $y%.2f / $p%.2f / $historic%.2f - winner: $winner")

            (flightCode, scheduled, y, p, historic, winner)
          })

        val numP = flightAndPred.count(_._6 == "P")
        val numH = flightAndPred.count(_._6 == "H")
        val phRatio = numP.toDouble / numH.toDouble
        //        println(s"p/h: $numP / $numH")

        val sanitisedNos = flightAndPred.filterNot {
          case (_, _, y, p, h, _) => y.isNaN || p.isNaN || h.isNaN
        }
        val numExamples = sanitisedNos.length
        val yMean = sanitisedNos.map(_._3).sum / numExamples
        val ssTot = sanitisedNos.map { case (_, _, y, _, _, _) => Math.pow(y - yMean, 2) }.sum
        val ssRes = sanitisedNos.map { case (_, _, y, _, h, _) => Math.pow(y - h, 2) }.sum
        val histR2 = 1 - (ssRes / ssTot)
        val histRmse = Math.sqrt(sanitisedNos.map(e => Math.pow(e._5 - e._3, 2)).sum / numExamples)

        val ssResP = sanitisedNos.map { case (_, _, y, p, _, _) => Math.pow(y - p, 2) }.sum
        val prR2 = 1 - (ssResP / ssTot)
        val prRmse = Math.sqrt(sanitisedNos.map(e => Math.pow(e._5 - e._4, 2)).sum / numExamples)

        val trainRmse = trainingSummary.rootMeanSquaredError
        val trainR2 = trainingSummary.r2

        val predRmse = summary.rootMeanSquaredError
        val predR2 = summary.r2

        (label, numExamples, f"$trainRmse%.2f", f"$trainR2%.2f", f"$predRmse%.2f", f"$predR2%.2f", f"$histRmse%.2f", f"$histR2%.2f", s"$numP", s"$numH", f"$phRatio%.2f")
      })
        .toDS
        .toDF("label", "examples", "Tr RMSE", "Tr r2", "Pr RMSE", "Pr r2", "Hi RMSE", "Hi r2", "Pr wins", "Hi wins", "P/H ratio")
        .show
      //        }
    }

    1 === 1
  }
}

/**
  *
  * STN T1
  * +--------------------+--------+-------+-----+-------+-----+-------+-------+-------+-------+---------+
  * |               label|examples|Tr RMSE|Tr r2|Pr RMSE|Pr r2|Hi RMSE|  Hi r2|Pr wins|Hi wins|P/H ratio|
  * +--------------------+--------+-------+-----+-------+-----+-------+-------+-------+-------+---------+
  * |  EeaMachineReadable|     537|   0.08| 0.86|   0.10| 0.75|   0.28|  -0.84|    417|    120|     3.48|
  * |EeaNonMachineRead...|     537|   0.08| 0.88|   0.10| 0.76|   0.40|  -2.42|    497|     36|    13.81|
  * |        VisaNational|     537|   0.02| 0.77|   0.02| 0.05|   0.21|-119.65|    535|      2|   267.50|
  * |     NonVisaNational|     537|   0.03| 0.68|   0.03| 0.62|   0.04|   0.09|    278|    232|     1.20|
  * +--------------------+--------+-------+-----+-------+-----+-------+-------+-------+-------+---------+
  * *
  * LHR T2
  * +--------------------+--------+-------+-----+-------+-----+-------+-----+-------+-------+---------+
  * |               label|examples|Tr RMSE|Tr r2|Pr RMSE|Pr r2|Hi RMSE|Hi r2|Pr wins|Hi wins|P/H ratio|
  * +--------------------+--------+-------+-----+-------+-----+-------+-----+-------+-------+---------+
  * |  EeaMachineReadable|     736|   0.09| 0.85|   0.14| 0.65|   0.21| 0.19|    538|    198|     2.72|
  * |EeaNonMachineRead...|     736|   0.05| 0.86|   0.06| 0.78|   0.16|-0.34|    285|     93|     3.06|
  * |        VisaNational|     736|   0.06| 0.88|   0.09| 0.68|   0.20|-0.55|    683|     53|    12.89|
  * |     NonVisaNational|     736|   0.07| 0.92|   0.12| 0.76|   0.13| 0.71|    421|    310|     1.36|
  * +--------------------+--------+-------+-----+-------+-----+-------+-----+-------+-------+---------+
  * *
  * LHR T3
  * +--------------------+--------+-------+-----+-------+-----+-------+-----+-------+-------+---------+
  * |               label|examples|Tr RMSE|Tr r2|Pr RMSE|Pr r2|Hi RMSE|Hi r2|Pr wins|Hi wins|P/H ratio|
  * +--------------------+--------+-------+-----+-------+-----+-------+-----+-------+-------+---------+
  * |  EeaMachineReadable|     619|   0.10| 0.82|   0.11| 0.69|   0.22|-0.21|    517|    102|     5.07|
  * |EeaNonMachineRead...|     619|   0.04| 0.71|   0.04| 0.54|   0.06|-0.08|    154|     84|     1.83|
  * |        VisaNational|     619|   0.05| 0.86|   0.08| 0.60|   0.20|-1.73|    580|     39|    14.87|
  * |     NonVisaNational|     619|   0.08| 0.89|   0.10| 0.75|   0.13| 0.59|    422|    195|     2.16|
  * +--------------------+--------+-------+-----+-------+-----+-------+-----+-------+-------+---------+
  * *
  * LHR T4
  * +--------------------+--------+-------+-----+-------+-----+-------+-----+-------+-------+---------+
  * |               label|examples|Tr RMSE|Tr r2|Pr RMSE|Pr r2|Hi RMSE|Hi r2|Pr wins|Hi wins|P/H ratio|
  * +--------------------+--------+-------+-----+-------+-----+-------+-----+-------+-------+---------+
  * |  EeaMachineReadable|     367|   0.09| 0.82|   0.11| 0.75|   0.17| 0.42|    243|    124|     1.96|
  * |EeaNonMachineRead...|     367|   0.03| 0.79|   0.02| 0.81|   0.06|-0.07|    108|     49|     2.20|
  * |        VisaNational|     367|   0.08| 0.89|   0.09| 0.86|   0.18| 0.42|    279|     88|     3.17|
  * |     NonVisaNational|     367|   0.07| 0.87|   0.08| 0.78|   0.09| 0.72|    210|    155|     1.35|
  * +--------------------+--------+-------+-----+-------+-----+-------+-----+-------+-------+---------+
  * *
  * LHR T5
  * +--------------------+--------+-------+-----+-------+-----+-------+-----+-------+-------+---------+
  * |               label|examples|Tr RMSE|Tr r2|Pr RMSE|Pr r2|Hi RMSE|Hi r2|Pr wins|Hi wins|P/H ratio|
  * +--------------------+--------+-------+-----+-------+-----+-------+-----+-------+-------+---------+
  * |  EeaMachineReadable|     743|   0.10| 0.75|   0.10| 0.64|   0.28|-1.65|    673|     70|     9.61|
  * |EeaNonMachineRead...|     743|   0.03| 0.69|   0.03| 0.35|   0.04|-0.07|     88|     75|     1.17|
  * |        VisaNational|     743|   0.06| 0.90|   0.05| 0.89|   0.25|-1.33|    702|     41|    17.12|
  * |     NonVisaNational|     743|   0.08| 0.83|   0.09| 0.68|   0.11| 0.46|    499|    243|     2.05|
  * +--------------------+--------+-------+-----+-------+-----+-------+-----+-------+-------+---------+
  * *
  * MAN T1
  * +--------------------+--------+-------+-----+-------+-----+-------+-----+-------+-------+---------+
  * |               label|examples|Tr RMSE|Tr r2|Pr RMSE|Pr r2|Hi RMSE|Hi r2|Pr wins|Hi wins|P/H ratio|
  * +--------------------+--------+-------+-----+-------+-----+-------+-----+-------+-------+---------+
  * |  EeaMachineReadable|     184|   0.08| 0.94|   0.10| 0.89|   0.36|-0.45|    122|     61|     2.00|
  * |EeaNonMachineRead...|     184|   0.05| 0.98|   0.06| 0.97|   0.35|-0.24|     97|     16|     6.06|
  * |        VisaNational|     184|   0.04| 0.76|   0.06| 0.50|   0.07| 0.36|     82|     78|     1.05|
  * |     NonVisaNational|     184|   0.05| 0.70|   0.06| 0.27|   0.06| 0.22|     79|     95|     0.83|
  * +--------------------+--------+-------+-----+-------+-----+-------+-----+-------+-------+---------+
  * *
  * MAN T2
  * +--------------------+--------+-------+-----+-------+---------+-------+-----+-------+-------+---------+
  * |               label|examples|Tr RMSE|Tr r2|Pr RMSE|    Pr r2|Hi RMSE|Hi r2|Pr wins|Hi wins|P/H ratio|
  * +--------------------+--------+-------+-----+-------+---------+-------+-----+-------+-------+---------+
  * |  EeaMachineReadable|      41|   0.08| 0.89|   0.06|     0.90|   0.12| 0.57|     31|     10|     3.10|
  * |EeaNonMachineRead...|      41|   0.00| 0.20|   0.00|      n/a|   0.00|  n/a|      0|      0|      n/a|
  * |        VisaNational|      41|   0.06| 0.93|   0.04|     0.94|   0.13| 0.53|     32|      4|     8.00|
  * |     NonVisaNational|      41|   0.05| 0.92|   0.04|     0.84|   0.06| 0.62|     29|      9|     3.22|
  * +--------------------+--------+-------+-----+-------+---------+-------+-----+-------+-------+---------+
  * *
  * MAN T3
  * +--------------------+--------+-------+-----+-------+-----+-------+-----+-------+-------+---------+
  * |               label|examples|Tr RMSE|Tr r2|Pr RMSE|Pr r2|Hi RMSE|Hi r2|Pr wins|Hi wins|P/H ratio|
  * +--------------------+--------+-------+-----+-------+-----+-------+-----+-------+-------+---------+
  * |  EeaMachineReadable|     151|   0.07| 0.78|   0.08| 0.76|   0.19|-0.27|    109|     42|     2.60|
  * |EeaNonMachineRead...|     151|   0.05| 0.84|   0.06| 0.85|   0.17|-0.22|     82|     31|     2.65|
  * |        VisaNational|     151|   0.03| 0.68|   0.03| 0.67|   0.04| 0.65|     79|     57|     1.39|
  * |     NonVisaNational|     151|   0.04| 0.74|   0.05| 0.61|   0.05| 0.52|     71|     75|     0.95|
  * +--------------------+--------+-------+-----+-------+-----+-------+-----+-------+-------+---------+
  */

/**
  * STN FR - fd fm fy fo
  * EeaMachineReadable, 0.08345330237998574, 0.8149199984848505 Vs validation: 0.09705607851045887, 0.7528684106387035
  * EeaNonMachineReadable, 0.08404142007252789, 0.8256014044875024 Vs validation: 0.09871933224734585, 0.7685501287374589
  * VisaNational, 0.01532152037264182, 0.6629525712661697 Vs validation: 0.018846100000395802, 0.637747379701673
  * NonVisaNational, 0.030556419469437462, 0.5601935452655205 Vs validation: 0.02951335644916006, 0.3707348917595735
  * *
  * STN FR - fd fm fy fo o
  * EeaMachineReadable, 0.08345330241283963, 0.8149199983391261 Vs validation: 0.096571129167812, 0.7553318707801171
  * EeaNonMachineReadable, 0.08404142011221975, 0.8256014043227692 Vs validation: 0.09820240391709019, 0.7709676845872468
  * VisaNational, 0.015321520376801895, 0.6629525710831405 Vs validation: 0.018822681079797364, 0.6386471196310683
  * NonVisaNational, 0.03055641948432429, 0.5601935448369806 Vs validation: 0.029542473930441588, 0.369492630245544
  * *
  * STN FR - fd fm fy fo d m y o
  * EeaMachineReadable, 0.08345330242545873, 0.8149199982831536 Vs validation: 0.09664892885131332, 0.7549374926525114
  * EeaNonMachineReadable, 0.08404142012626109, 0.8256014042644935 Vs validation: 0.09829300278190874, 0.77054489168852
  * VisaNational, 0.015321520381049107, 0.6629525708962777 Vs validation: 0.018824150544075827, 0.6385906966513184
  * NonVisaNational, 0.030556419506018614, 0.5601935442124766 Vs validation: 0.02954183105041956, 0.36952007115535823
  * *
  * STN FR - fd fm fy dm y o
  * EeaMachineReadable, 0.08286106265246362, 0.8175375771295267 Vs validation: 0.10458130056374684, 0.7130601551788893
  * EeaNonMachineReadable, 0.0832851260345319, 0.828726129055861 Vs validation: 0.10801774598060665, 0.7228960308313146
  * VisaNational, 0.01529279723952943, 0.6642151070229192 Vs validation: 0.018887255820019325, 0.6361634888908144
  * NonVisaNational, 0.030356237883623273, 0.5659372002452119 Vs validation: 0.03018787235499658, 0.34164300350629795
  * *
  * STN FR - fd fm fo y o
  * EeaMachineReadable, 0.08345330239198472, 0.8149199984316288 Vs validation: 0.09682068585905387, 0.7540657064032741
  * EeaNonMachineReadable, 0.0840414200919133, 0.825601404407047 Vs validation: 0.09854906842000943, 0.769347815602037
  * VisaNational, 0.015321520369730718, 0.6629525713942483 Vs validation: 0.01883344757765851, 0.6382336166158791
  * NonVisaNational, 0.03055641947512156, 0.5601935451018951 Vs validation: 0.029391377196634646, 0.375925671603615
  */
