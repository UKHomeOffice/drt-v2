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
import services.{CSVPassengerSplitsProvider, CsvPassengerSplitsReader, SDate}

import scala.collection.immutable

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
      .csv("/home/rich/dev/man-splits-from-api.csv")

    stuff.createOrReplaceTempView("splits")

    stuff.printSchema()

    import org.apache.spark.sql.functions._


    //    val carrier = "AA"
    //    val carrierLike =s"""LIKE "$carrier%""""
    val flights = Map(
      "LHR-T2" -> List(
        "A30600", "A30602", "A30608", "AC0848", "AC0850", "AC0854", "AC0858", "AC0864", "AC0868", "AC0888", "AI0111", "AI0131",
        "AI0161", "AI0171", "AV0120", "BR0067", "CA0855", "CA0937", "ET0710", "EW0460", "EW0464", "EW0468", "EW2460", "EW2462",
        "EW2464", "EW7460", "EW7462", "EW7464", "EW8460", "EW8462", "EW8464", "EW9462", "EW9464", "EW9466", "EW9468", "FI0450",
        "FI0450", "FI0454", "LH0900", "LH0902", "LH0904", "LH0906", "LH0908", "LH0910", "LH0914", "LH0916", "LH0918", "LH0920",
        "LH0922", "LH0924", "LH2470", "LH2472", "LH2474", "LH2476", "LH2478", "LH2480", "LH2482", "LH2484", "LO0279", "LO0281",
        "LO0285", "LX0316", "LX0318", "LX0324", "LX0326", "LX0332", "LX0338", "LX0340", "LX0348", "LX0352", "LX0354", "LX0356",
        "LX0358", "MS0761", "MS0777", "MS0779", "NH0211", "NZ0002", "OS0451", "OS0455", "OU0490", "OZ0521", "SA0234", "SA0236",
        "SK0501", "SK0503", "SK0505", "SK0525", "SK0527", "SK0531", "SK0533", "SK0803", "SK0805", "SK0809", "SK0811", "SK0815",
        "SK1501", "SK1507", "SK1517", "SK1523", "SK1527", "SK4627", "SK4629", "SN2093", "SN2095", "SN2103", "SQ0306", "SQ0308",
        "SQ0318", "SQ0322", "TG0910", "TG0916", "TK1971", "TK1979", "TK1983", "TK1985", "TK1987", "TP0356", "TP0358", "TP0366",
        "TP0366", "TP0372", "UA0005", "UA0014", "UA0016", "UA0110", "UA0122", "UA0880", "UA0901", "UA0918", "UA0923", "UA0924",
        "UA0930", "UA0931", "UA0934", "UA0938", "UA0940", "UA0958"),
      "LHR-T3" -> List(
        "AA0038", "AA0046", "AA0050", "AA0056", "AA0078", "AA0090", "AA0100", "AA0104", "AA0106", "AA0108", "AA0136", "AA0142",
        "AA0174", "AA0728", "AA0730", "AA0736", "AY1331", "AY1335", "AY1337", "AY1339", "AY1341", "BA0042", "BA0058", "BA0064",
        "BA0078", "BA0084", "BA0206", "BA0208", "BA0218", "BA0274", "BA0288", "BA0359", "BA0361", "BA0365", "BA0367", "BA0369",
        "BA0371", "BA0417", "BA0419", "BA0467", "BA0469", "BA0471", "BA0473", "BA0475", "BA0477", "BA0479", "BA0481", "BA0487",
        "BA0491", "BA0499", "BA0501", "BA0503", "BA0697", "BA0699", "BA0701", "BA0703", "BA0705", "BA0795", "BA0799", "BA0847",
        "BA0851", "BA0853", "BA0855", "BA0857", "BA0859", "BA0865", "BA0867", "BA0869", "CX0237", "CX0251", "CX0253", "CX0255",
        "CX0257", "DL0016", "DL0018", "DL0030", "DL0058", "DL0172", "DL0284", "DL0401", "DL0402", "DL0403", "EK0001", "EK0003",
        "EK0005", "EK0007", "EK0029", "EK0031", "JD0431", "JJ8084", "JL0041", "JL0043", "ME0201", "ME0203", "PK0785", "PR0720",
        "QF0001", "QF0009", "RJ0111", "UL0503", "VS0002", "VS0004", "VS0008", "VS0010", "VS0012", "VS0020", "VS0022", "VS0026",
        "VS0042", "VS0106", "VS0118", "VS0138", "VS0207", "VS0251"),
      "LHR-T4" -> List("9W0116", "9W0118", "9W0120", "9W0122", "AF1080", "AF1180", "AF1280", "AF1380", "AF1580", "AF1680", "AF1780", "AH2054", "AT0800", "AZ0204", "AZ0208", "AZ0210", "AZ0238", "AZ0248", "BI0097", "CZ0303", "CZ0603", "EY0011", "EY0017", "EY0019", "FB0851", "GF0003", "GF0007", "JU0380", "KC0941", "KE0907", "KL1001", "KL1007", "KL1009", "KL1011", "KL1017", "KL1019", "KL1021", "KL1023", "KL1027", "KL1031", "KL1033", "KM0100", "KM0102", "KQ0100", "KU0101", "LY0315", "LY0317", "MH0002", "MH0004", "MU0551", "QR0001", "QR0003", "QR0005", "QR0007", "QR0009", "QR0015", "RO0391", "SU0263", "SU2572", "SU2578", "SU2580", "SV0117", "VN0051", "WY0101", "WY0103"),
      "LHR-T5" -> List("BA0006", "BA0008", "BA0010", "BA0012", "BA0016", "BA0018", "BA0028", "BA0032", "BA0034", "BA0036", "BA0038", "BA0048", "BA0054", "BA0056", "BA0066", "BA0072", "BA0074", "BA0080", "BA0082", "BA0094", "BA0098", "BA0102", "BA0104", "BA0106", "BA0108", "BA0112", "BA0114", "BA0116", "BA0118", "BA0122", "BA0124", "BA0132", "BA0138", "BA0142", "BA0146", "BA0148", "BA0152", "BA0154", "BA0156", "BA0160", "BA0162", "BA0164", "BA0168", "BA0172", "BA0174", "BA0176", "BA0178", "BA0182", "BA0184", "BA0188", "BA0190", "BA0192", "BA0194", "BA0196", "BA0198", "BA0212", "BA0214", "BA0216", "BA0226", "BA0228", "BA0232", "BA0234", "BA0236", "BA0238", "BA0242", "BA0244", "BA0246", "BA0248", "BA0250", "BA0252", "BA0256", "BA0262", "BA0268", "BA0272", "BA0276", "BA0278", "BA0280", "BA0282", "BA0284", "BA0286", "BA0292", "BA0294", "BA0296", "BA0303", "BA0307", "BA0309", "BA0315", "BA0319", "BA0323", "BA0327", "BA0329", "BA0341", "BA0343", "BA0345", "BA0347", "BA0349", "BA0373", "BA0377", "BA0379", "BA0389", "BA0391", "BA0393", "BA0397", "BA0399", "BA0403", "BA0423", "BA0427", "BA0429", "BA0431", "BA0435", "BA0439", "BA0441", "BA0443", "BA0447"),
      "STN-T1" -> List("CO0640", "EW0354", "EW0356", "EW1752", "EW2376", "EW2378", "EW3370", "EW4392", "EW5832", "U23002", "U23004", "U23006", "U23010", "U23064", "U23068", "U23072", "U23084", "U23104", "U23112", "U23132", "U23140", "U23214", "U23226", "U23246", "U23252", "FR0012", "FR0014", "FR0053", "FR0059", "FR0144", "FR0146", "FR0169", "FR0195", "FR0291", "FR0296", "FR0305", "FR0465", "FR0515", "FR0585", "FR0683", "FR0713", "FR0753", "FR0793", "FR0795", "FR0799", "FR0965", "FR0967", "FR0983", "FR1006", "FR1014", "FR1022", "FR1081", "FR1195", "FR1319", "FR1395", "FR1397", "FR1519", "FR1546", "FR1599", "FR1671", "FR1686", "FR1783", "FR1789", "FR1833", "FR1837", "FR1875", "FR1883", "FR1885", "FR1906", "FR2014", "FR2137", "FR2145", "FR2224", "FR2245", "FR2282", "FR2284", "FR2315", "FR2319", "FR2337", "FR2373", "FR2375", "FR2405", "FR2433", "FR2435", "FR2437", "FR2463", "FR2467", "FR2469", "FR2613", "FR2643", "FR2645", "FR2670", "FR2673", "FR2683", "FR2813", "FR2815", "FR2817", "FR2861", "FR3003", "FR3005", "FR3015", "FR3073", "FR3132", "FR3253", "FR3557", "FR3631", "FR3633", "FR3843", "FR3919", "FR4191", "FR4193", "FR4195", "FR4678", "FR4953", "FR4967", "FR5173", "FR5179", "FR5995", "FR5997", "FR6542", "FR7381", "FR7383", "FR7385", "FR8027", "FR8116", "FR8120", "FR8133", "FR8163", "FR8165", "FR8167", "FR8183", "FR8267", "FR8289", "FR8322", "FR8344", "FR8348", "FR8354", "FR8362", "FR8364", "FR8369", "FR8383", "FR8387", "FR8397", "FR8404", "FR8406", "FR8408", "FR8446", "FR8543", "FR8545", "FR8583", "FR8737", "FR8777", "FR8869", "FR9015", "FR9045", "FR9272", "FR9274", "FR9276", "FR9773", "FR9815", "FR9962", "FR9968", "KK2001", "LS1406", "LS1412", "LS1446", "LS1462", "LS1530", "LS1664", "PC1163", "PC1169", "PC1181", "BY5117", "AZ0212", "AZ0216"),
      "MAN-T1" -> List("AY1361", "AY1365", "CO0658", "DY1326", "DY1348", "DY4479", "EK0017", "EK0019", "EK0021", "EW0342", "EW4388", "EW7344", "EW9340", "EW9342", "EW9344", "EY0015", "EY0021", "EZY1802", "EZY1806", "EZY1812", "EZY1816", "EZY1828", "EZY1832", "EZY1834", "EZY1836", "EZY1838", "EZY1842", "EZY1844", "EZY1864", "EZY1882", "EZY1888", "EZY1896", "EZY1898", "EZY1902", "EZY1918", "EZY1924", "EZY1926", "EZY1928", "EZY1932", "EZY1950", "EZY1954", "EZY1972", "EZY1974", "EZY1984", "EZY1986", "EZY1996", "EZY2921", "EZY3411", "FI0440", "LH0938", "LH0940", "LH0942", "LH0946", "LH0948", "LH2500", "LH2502", "LH2504", "LS0766", "LS0792", "LS0802", "LS0804", "LS0810", "LS0880", "LS0888", "LS0892", "LS0898", "LS0918", "LS0950", "LX0380", "LX0390", "MT0487", "MT1165", "MT1661", "MT1723", "MT1725", "MT1845", "MT1967", "MT2714", "MT2843", "OS0463", "SK0539", "SK0541", "SK2547", "SK2549", "SK4605", "SK4609", "SN2173", "SN2177", "SN2183", "TK1993", "TK1995", "TP0322", "TP0324", "LS0876", "MT0873"),
      "MAN-T2" -> List("CX0219", "HU7903", "PK0701", "QR0021", "QR0023", "SQ0051", "TOM0109", "TOM0183", "TOM0289", "TOM0425", "TOM0451", "TOM0587", "TOM2103", "TOM2107", "TOM2135", "TOM2147", "TOM2707", "TOM2769", "UA0081", "VS0076", "VS0078", "VS0128", "WY0105", "QR0027", "TOM2717"),
      "MAN-T3" -> List("AA0734", "AF1068", "AF1168", "AF1668", "BE1266", "BE1272", "BE1274", "BE1280", "BE1282", "BE3122", "BE3124", "BE3128", "BE3132", "BE3232", "BE3254", "BE7212", "BE7216", "BE7218", "BE7308", "BE7352", "BA8245", "BA8247", "FR0037", "FR1144", "FR1592", "FR2142", "FR2418", "FR2603", "FR3186", "FR3205", "FR3227", "FR3235", "FR3239", "FR3247", "FR3249", "FR3504", "FR3804", "FR3806", "FR3953", "FR4006", "FR4051", "FR4117", "FR4331", "FR4371", "FR5210", "FR7543", "FR8307", "KL1073", "KL1081", "KL1083", "KL1093", "KQ1097", "VY8748")
    )

    val portCode = "MAN"
    val flightFilterWhereClause = s"""flight IN ("${flights("MAN-T1").mkString("\",\"")}") AND dest="$portCode" """
    val featureSpecs = List(
      FeatureSpec(List("flight", "day"), flightFilterWhereClause, "fd"),
      FeatureSpec(List("flight", "month"), flightFilterWhereClause, "fm"),
      FeatureSpec(List("flight", "year"), flightFilterWhereClause, "fy"),
      FeatureSpec(List("flight", "origin"), flightFilterWhereClause, "fo"),
      FeatureSpec(List("day"), flightFilterWhereClause, "d"),
      FeatureSpec(List("month"), flightFilterWhereClause, "m"),
      FeatureSpec(List("year"), flightFilterWhereClause, "y"),
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

    val cutOffDate = "2018-02-01"

    val flightsToTrain = stuff
      .where(expr(flightFilterWhereClause))
      .where(col("scheduled") < cutOffDate)
      .groupBy(col("flight"))
      .count
      .withColumnRenamed("count", "numExamples")
      .filter("numExamples >= 50")
      .collect().toSeq
      .map(_.getAs[String]("flight"))

    val whereClause = s"""flight IN ("${flightsToTrain.mkString("\",\"")}") AND dest="$portCode" """

    List("EeaMachineReadable", "EeaNonMachineReadable", "VisaNational", "NonVisaNational").map(label => {
      val labelAndFeatures = col(label) :: featureSpecs.map(fs => concat_ws("-", fs.columns.map(col): _*)) ++ List(col("flight"), col("scheduled"))

      val trainingSet = stuff
        .select(labelAndFeatures: _*)
        .where(expr(whereClause))
        .where(col("scheduled") < cutOffDate)
        //        .where(col("scheduled") between("2017-07-01", cutOffDate))
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
        .where(col("scheduled") >= cutOffDate)
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

      //        println(s"$label has summary: ${lrModel.hasSummary}")

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
            case (diffP, diffH) if Math.abs(diffP - diffH) < 0.001 => "n/a"
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
