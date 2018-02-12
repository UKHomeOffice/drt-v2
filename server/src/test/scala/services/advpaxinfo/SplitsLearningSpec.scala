package services.advpaxinfo

import java.sql.Timestamp

import com.typesafe.config.ConfigFactory
import drt.shared.MilliDate
import drt.shared.SplitRatiosNs.SplitRatios
import org.apache.spark.ml.clustering.KMeans
import org.apache.spark.ml.feature.LabeledPoint
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.ml.regression.LinearRegression
import org.apache.spark.sql.functions.col
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import services.{CSVPassengerSplitsProvider, CsvPassengerSplitsReader}

case class FeatureSpec(columns: List[String], whereClause: String, featurePrefix: String)

case class SplitPrediction(flight: String, scheduled: MilliDate, archetype: String, prediction: Double)

object Splits {
  val historic = CSVPassengerSplitsProvider(CsvPassengerSplitsReader.flightPaxSplitsLinesFromConfig).splitRatioProvider
  val historicRaw = CSVPassengerSplitsProvider(CsvPassengerSplitsReader.flightPaxSplitsLinesFromConfig).getFlightSplitRatios _
}

class SplitsLearningSpec extends Specification {
  val rawZipFilesPath: String = ConfigFactory.load.getString("dq.raw_zip_files_path")

  "I can manipulate DataFrame / Dataset / rdd columns etc" >> {
    skipped("until we know whether clustering will help us")
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
      .csv("/home/rich/dev/all-splits-from-api.csv")

    stuff.createOrReplaceTempView("splits")

    val splitsForClustering = stuff
      .select(col("EeaMachineReadable"), col("day"), col("month"))
      .where(col("dest") === "STN")
      .where(col("scheduled") between("2017-12-05", "2018-02-05"))
      .collect.toSeq
      .map(row => {
        val vector = Vectors.dense(row.getAs[Double](0), row.getAs[Int](1), row.getAs[Int](2))
        (row.getAs[Double](0), row.getAs[Int](1), row.getAs[Int](2), vector)
      })
      .toDS()
      .toDF("eea", "day", "month", "features")

    val kmeans = new KMeans()
    val numClusters = 10
    val clusterModel = kmeans
      .setK(numClusters)
      .setFeaturesCol("features")
      .fit(splitsForClustering)

    val summary = clusterModel.summary
    println(s"${summary.clusterSizes}")

    // Evaluate clustering by computing Within Set Sum of Squared Errors.
    val WSSSE = clusterModel.computeCost(splitsForClustering)
    println(s"Within Set Sum of Squared Errors = $WSSSE")

    // Shows the result.
    println("Cluster Centers: ")
    clusterModel.clusterCenters.foreach(println)

    val clusteringSet = stuff
      .select(col("EeaMachineReadable"), col("day"), col("month"))
      .where(col("dest") === "STN")
      .where(col("scheduled") between("2017-12-05", "2018-02-05"))
      .collect.toSeq
      .map(row => {
        val vector = Vectors.dense(row.getAs[Double](0), row.getAs[Int](1), row.getAs[Int](2))
        (row.getAs[Double](0), row.getAs[Int](1), row.getAs[Int](2), vector)
      })
      .toDS()
      .toDF("eea", "day", "month", "features")

    val clusteredSet = clusterModel.transform(clusteringSet)


    clusteredSet.printSchema()
    clusteredSet.collect.map(r => {
      println(s"row: $r")
    })
    clusteredSet.show()

    true
  }


  //  val historicSplits: (String, MilliDate) => Option[SplitRatios] =
  //    CSVPassengerSplitsProvider(CsvPassengerSplitsReader.flightPaxSplitsLinesFromConfig).splitRatioProvider


  "I can read a splits csv into spark" >> {
    //    skipped("for now..")
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
      .csv("/home/rich/dev/BA-lhr-splits-from-api.csv")

    stuff.createOrReplaceTempView("splits")

    stuff.printSchema()

    import org.apache.spark.sql.functions._


    //    val carrier = "AA"
    //    val carrierLike =s"""LIKE "$carrier%""""
    val portCode = "LHR"
    val whereClause = s"""dest="$portCode""""
    val featureSpecs = List(
      FeatureSpec(List("flight", "day"), whereClause, "fd"),
      FeatureSpec(List("flight", "month"), whereClause, "fm"),
      FeatureSpec(List("flight", "year"), whereClause, "fy"),
      FeatureSpec(List("flight", "origin"), whereClause, "fo"),
      FeatureSpec(List("day"), whereClause, "d"),
      FeatureSpec(List("month"), whereClause, "m"),
      FeatureSpec(List("year"), whereClause, "y"),
      FeatureSpec(List("origin"), whereClause, "o")
    )
    val features = featureSpecs.flatMap(fs => {
      stuff
        .select(concat_ws("-", fs.columns.map(col): _*))
        .where(expr(fs.whereClause))
        .rdd.distinct.collect
        .map(fs.featurePrefix + _.getAs[String](0))
    }).toIndexedSeq
    println(s"features: $features")


    val files = SplitsExport.getListOfFiles(rawZipFilesPath)

    val stats = List("EeaMachineReadable", "EeaNonMachineReadable", "VisaNational", "NonVisaNational").map(label => {
      val labelAndFeatures = col(label) :: featureSpecs.map(fs => concat_ws("-", fs.columns.map(col): _*)) ++ List(col("flight"), col("scheduled"))

      val trainingSet = stuff
        .select(labelAndFeatures: _*)
        .where(expr(whereClause))
        .where(col("scheduled") < "2018-01-01")
        //        .where(col("scheduled") between("2017-10-05", "2018-01-20"))
        .map(row => {
        val sf = featureSpecs
          .zipWithIndex
          .map {
            case (fs, idx) =>
              val featureString = s"${fs.featurePrefix}${row.getAs[String](idx + 1)}"
              val featureIdx = features.indexOf(featureString)
              (featureIdx, 1d)
          }

        LabeledPoint(row.getAs[Double](0), Vectors.sparse(features.length, sf))
      }).cache()


      val lr = new LinearRegression()
      val lrModel = lr.fit(trainingSet)
      val trainingSummary = lrModel.summary

      val validationSet = stuff
        .select(labelAndFeatures: _*)
        .where(expr(whereClause))
        .where(col("scheduled") >= "2018-01-01")
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

          val flightCode = row.getAs[String]("flight")
          val scheduledTs = row.getAs[Timestamp]("scheduled")
          val scheduled = MilliDate(scheduledTs.getTime)
          val y = row.getAs[Double](label)
          val sparseFeatures = Vectors.sparse(features.length, sf)

          val historic: Option[Double] = Splits.historic(flightCode, scheduled).map(sr => {
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

      val summary = lrModel.evaluate(validationSet)
      val withPrediction = lrModel.transform(validationSet)

      val flightAndPred = withPrediction
        .collect.toSeq
        .map(row => {
          val flightCode = row.getAs[String]("flight")
          val scheduled = row.getAs[Timestamp]("scheduled")

          val historic = row.getAs[Double]("historic")

          val y = row.getAs[Double]("label")
          val p = row.getAs[Double]("prediction")

          val winner = (Math.abs(y - p), Math.abs(y - historic)) match {
            case (diffP, diffH) if diffP <= diffH => s"P"//: ${diffH - diffP}"
            case (diffP, diffH) if diffP > diffH => s"H"//: ${diffP - diffH}"
            case _ => "n/a"
          }

//          println(f"$label: $y%.2f / $p%.2f / $historic%.2f - winner: $winner")

          (flightCode, scheduled, y, p, historic, winner)
        })

      val numP = flightAndPred.count(_._6 == "P")
      val numH = flightAndPred.count(_._6 == "H")
      println(s"p/h: $numP / $numH")

      val numExamples = flightAndPred.length
      val yMean = flightAndPred.map(_._3).sum / numExamples
      val ssTot = flightAndPred.map { case (_, _, y, _, _, _) => Math.pow(y - yMean, 2) }.sum
      val ssRes = flightAndPred.map { case (_, _, y, _, h, _) => Math.pow(y - h, 2) }.sum
      val histR2 = 1 - (ssRes / ssTot)
      val histRmse = Math.sqrt(flightAndPred.map(e => Math.pow(e._5 - e._3, 2)).sum / numExamples)

      val ssResP = flightAndPred.map { case (_, _, y, p, _, _) => Math.pow(y - p, 2) }.sum
      val histR2P = 1 - (ssResP / ssTot)
      val histRmseP = Math.sqrt(flightAndPred.map(e => Math.pow(e._5 - e._4, 2)).sum / numExamples)

      val trainRmse = trainingSummary.rootMeanSquaredError
      val trainR2 = trainingSummary.r2

      val predRmse = summary.rootMeanSquaredError
      val predR2 = summary.r2

      f"$numExamples examples, train: RMSE $trainRmse%.2f, $trainR2%.2f Vs validation: RMSE $predRmse%.2f, $predR2%.2f Vs hist: RMSE: $histRmse%.2f, r2: $histR2%.2f Vs pred: RMSE: $histRmseP%.2f, r2: $histR2P%.2f: $label"
    })

    stats.foreach(println)

    1 === 1
  }
}

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
