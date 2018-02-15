package services.advpaxinfo

import java.io.{BufferedWriter, File, FileWriter, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Paths
import java.util.zip.ZipInputStream

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Source, StreamConverters}
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import controllers.ArrivalGenerator
import drt.shared._
import org.apache.spark.ml.clustering.KMeans
import org.apache.spark.ml.feature.LabeledPoint
import org.apache.spark.ml.regression.{LinearRegression, LinearRegressionModel}
import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions.{col, concat_ws, expr}
import org.apache.spark.sql.types.{ArrayType, StringType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row, SQLContext, SparkSession}
import org.specs2.mutable.Specification
import passengersplits.core.SplitsCalculator
import passengersplits.parsing.VoyageManifestParser
import services.Manifests.log
import services.{Manifests, SDate}

import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success, Try}


class SplitsExportSpec extends Specification {
  val rawZipFilesPath: String = ConfigFactory.load.getString("dq.raw_zip_files_path")

  "Looking at raw API data" >> {

    "I can list the files in the atmos-backup dir" >> {
      skipped("These were used to help write the exporting code for real API files which are not normally accessible")
      val files = SplitsExport.getListOfFiles(rawZipFilesPath)

      files.length !== 0
    }

    "I can unzip the zips" >> {
      skipped("These were used to help write the exporting code for real API files which are not normally accessible")
      val files = SplitsExport.getListOfFiles(rawZipFilesPath)

      val content: List[String] = SplitsExport.extractFilesFromZips(files.take(1), List("FR"))

      content.head.length !== 0
    }

    "I can produce an csv export of nationalities" >> {
      skipped("These were used to help write the exporting code for real API files which are not normally accessible")
      val files = SplitsExport.getListOfFiles(rawZipFilesPath)

      val carriers = List(
        "AB", "NY", "AP", "IZ", "5O", "AG", "5Y", "S4", "8H", "0B", "OK", "J7", "WK", "OF", "5F", "FH", "FH",
        "4U", "5K", "6H", "B0", "VL", "IG", "NL", "SX", "RE", "ZT", "VG", "W6", "8Z",
        "JP", "AP", "IZ", "5O", "BM", "V3", "V4", "WX", "DE", "OU", "J7", "T3", "T4", "OF", "E9", "NY", "5F",
        "ST", "IB", "I2", "6H", "LG", "IG", "YM", "HG", "SX", "EZ", "ZT", "HV", "OR", "WF", "WW")

      val filesToInclude = files.sortBy(_.getAbsolutePath)
      val (nations, summaries) = SplitsExport.extractSummariesFromZips(filesToInclude, carriers)

      SplitsExport.writeCsvReport(nations, summaries, "/tmp/apiNats.csv")

      1 !== 0
    }

    "I can combine flight summaries into csv lines" >> {
      val summaries = List(
        FlightSummary("BY7339", "2017-10-11", "21:00:00", "EMA", isInteractive = Option(false), Map("GBR" -> 198, "BLZ" -> 1, "LTU" -> 1)),
        FlightSummary("BY7339", "2017-10-12", "21:00:00", "EMA", isInteractive = Option(false), Map("GBR" -> 150, "FRA" -> 20)))

      val expected = List(
        FlightSummary("BY7339", "2017-10-11", "21:00:00", "EMA", isInteractive = Option(false), Map("GBR" -> 198, "BLZ" -> 1, "LTU" -> 1, "FRA" -> 0)),
        FlightSummary("BY7339", "2017-10-12", "21:00:00", "EMA", isInteractive = Option(false), Map("GBR" -> 150, "BLZ" -> 0, "LTU" -> 0, "FRA" -> 20)))

      val (_, result) = SplitsExport.expandToFullNationalities(summaries)

      result === expected
    }

    "I can get export splits from API to csv" >> {
      skipped("not now..")
      import akka.stream.scaladsl._

      val files = SplitsExport.getListOfFiles(rawZipFilesPath)

      val manifests = files.sortBy(_.getName).flatMap(file => {
        val byteStringSource = FileIO.fromPath(Paths.get(file.getAbsolutePath))
        val flightCodesToSelect = Option(List("FR", "U2"))
        Manifests
          .fileNameAndContentFromZip(file.getName, byteStringSource, Option("STN"), flightCodesToSelect)
          .map {
            case (_, vm) => vm
          }
      })

      val archetypes = List(
        Tuple2(PaxTypes.EeaMachineReadable, Queues.EeaDesk),
        Tuple2(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk),
        Tuple2(PaxTypes.VisaNational, Queues.NonEeaDesk),
        Tuple2(PaxTypes.NonVisaNational, Queues.NonEeaDesk)
      )

      val historicSplits: List[HistoricSplitsCollection] = SplitsExport
        .historicSplitsCollection("STN", manifests)
        .sortBy(h => h.scheduled.millisSinceEpoch)
      val averageSplits = SplitsExport.averageFlightSplitsByMonthAndDay(historicSplits, archetypes)

      val filePath = "/tmp/historic-splits-from-api.csv"
      val file = new File(filePath)
      val bw = new BufferedWriter(new FileWriter(file))

      val paxTypes = archetypes.map(_._1.name.dropRight(1)).mkString(",")
      val paxTypeAvgs = archetypes.map(a => s"${a._1.name.dropRight(1)} avg").mkString(",")
      bw.write(s"flight,scheduled,origin,dest,year,month,day,$paxTypes,$paxTypeAvgs\n")

      historicSplits
        .foreach(s => {
          val year = SDate(s.scheduled).getFullYear()
          val month = SDate(s.scheduled).getMonth()
          val dayOfWeek = SDate(s.scheduled).getDayOfWeek()
          val key = (s.flightCode, year - 1, month, dayOfWeek)
          val average = averageSplits.get(key) match {
            case None => List.fill(archetypes.length)(0d)
            case Some(avgs) =>
              println(s"Found historic avgs for $key: $avgs")
              avgs
          }
          val actuals = archetypes.map {
            case (paxType, queueName) =>
              s.splits.find {
                case ApiPaxTypeAndQueueCount(pt, qn, _, _) => pt == paxType && qn == queueName
              }.map(_.paxCount).getOrElse(0)
          }
          val row = s"${s.flightCode},${SDate(s.scheduled).ddMMyyString},${s.originPort},${s.arrivalPort},$year,$month,$dayOfWeek,${actuals.mkString(",")},${average.mkString(",")}\n"
          bw.write(row)
        })

      bw.close()

      //      println(averageSplits)

      1 === 1
    }

    val archetypes = List(
      Tuple2(PaxTypes.EeaMachineReadable.cleanName, Queues.EeaDesk),
      Tuple2(PaxTypes.EeaNonMachineReadable.cleanName, Queues.EeaDesk),
      Tuple2(PaxTypes.VisaNational.cleanName, Queues.NonEeaDesk),
      Tuple2(PaxTypes.NonVisaNational.cleanName, Queues.NonEeaDesk)
    )

    implicit val actorSystem: ActorSystem = ActorSystem("AdvPaxInfo")
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    def writeSplitsFromZip[X](zipFileName: String,
                              zippedFileByteStream: Source[ByteString, X],
                              outputFile: BufferedWriter): Unit = {

      val inputStream: InputStream = zippedFileByteStream.runWith(
        StreamConverters.asInputStream()
      )
      val zipInputStream = new ZipInputStream(inputStream)

      Stream
        .continually(zipInputStream.getNextEntry)
        .takeWhile(_ != null)
        .foreach { _ =>
          val buffer = new Array[Byte](4096)
          val stringBuffer = new ArrayBuffer[Byte]()
          var len: Int = zipInputStream.read(buffer)

          while (len > 0) {
            stringBuffer ++= buffer.take(len)
            len = zipInputStream.read(buffer)
          }
          val jsonContent: String = new String(stringBuffer.toArray, UTF_8)
          parseJsonAndWrite(archetypes, outputFile, jsonContent)
        }

      log.info(s"Finished processing $zipFileName")

      //      outputFile.flush()
    }

    "I can get export splits from API directly to csv" >> {
      skipped("no need to export the csv every time")
      import akka.stream.scaladsl._

      val files = SplitsExport.getListOfFiles(rawZipFilesPath)

      val filePath = "/home/rich/dev/all-splits-from-api.csv"
      val file = new File(filePath)
      val bw = new BufferedWriter(new FileWriter(file))
      val paxTypes = archetypes.map(_._1).mkString(",")
      bw.write(s"flight,scheduled,origin,dest,year,month,day,$paxTypes\n")

      files.sortBy(_.getName).foreach(file => {
        val byteStringSource = FileIO.fromPath(Paths.get(file.getAbsolutePath))
        writeSplitsFromZip(file.getName, byteStringSource, bw)
      })

      bw.close()

      1 === 1
    }

    "I can train a regression model on a specific flight and get its splits back" >> {
      skipped("let's prove ml is better than historic averages first")
      import org.apache.spark.sql.SparkSession

      val sparkSession: SparkSession = SparkSession
        .builder
        .appName("Simple Application")
        .config("spark.master", "local")
        .getOrCreate()

      val splitsData: DataFrame = sparkSession
        .read
        .option("header", "true")
        .option("inferSchema", "true")
        //        .csv("/tmp/all-splits-from-api.csv")
        .csv("/home/rich/dev/all-splits-from-api.csv")

      splitsData.createOrReplaceTempView("splits")
      splitsData.printSchema()

      val sqlContext = sparkSession.sqlContext
      //      val predictor = SplitsPredictor(sparkSession, splitsData)
      val arrival = ArrivalGenerator.apiFlight(iata = "BA0907", schDt = "2018-02-06T10:05:00Z")

      val iataCode = arrival.IATA
      val carrierEquals =s"""LIKE "$iataCode""""

      val featuresWithFd = sqlContext.sql(s"""SELECT DISTINCT CONCAT(flight,"-",day) FROM splits WHERE flight $carrierEquals""")
        .rdd.map(_.getAs[String](0)).collect()
        .foldLeft(IndexedSeq[String]()) {
          case (fts, feature) => fts :+ s"fd$feature"
        }
      val featuresWithFm = sqlContext.sql(s"""SELECT DISTINCT CONCAT(flight,"-",month) FROM splits WHERE flight $carrierEquals""")
        .rdd.map(_.getAs[String](0)).collect()
        .foldLeft(featuresWithFd) {
          case (fts, feature) => fts :+ s"fm$feature"
        }
      val featuresWithFy = sqlContext.sql(s"""SELECT DISTINCT CONCAT(flight,"-",year) FROM splits WHERE flight $carrierEquals""")
        .rdd.map(_.getAs[String](0)).collect()
        .foldLeft(featuresWithFm) {
          case (fts, feature) => fts :+ s"fy$feature"
        }
      val featuresWithFo = sqlContext.sql(s"""SELECT DISTINCT CONCAT(flight,"-",origin) FROM splits WHERE flight $carrierEquals""")
        .rdd.map(_.getAs[String](0)).collect()
        .foldLeft(featuresWithFy) {
          case (fts, feature) => fts :+ s"fo$feature"
        }

      val features = featuresWithFo

      val labels = List("EeaMachineReadable", "EeaNonMachineReadable", "VisaNational", "NonVisaNational")

      import sparkSession.implicits._

      val stats = labels.map(label => {
        val trainingSet = splitsData
          .select(
            col(label),
            concat_ws("-", col("flight"), col("day")),
            concat_ws("-", col("flight"), col("month")),
            concat_ws("-", col("flight"), col("year")),
            concat_ws("-", col("flight"), col("origin"))
          )
          .where(col("flight") === iataCode)
          .map(r => {
            val sparseFeatures = Seq(
              (features.indexOf(s"fd${r.getAs[String](1)}"), 1.0),
              (features.indexOf(s"fm${r.getAs[String](2)}"), 1.0),
              (features.indexOf(s"fy${r.getAs[String](3)}"), 1.0)
              //              (features.indexOf(s"fo${r.getAs[String](4)}"), 1.0)
            )

            LabeledPoint(
              r.getAs[Double](0),
              Vectors.sparse(features.length, sparseFeatures)
            )
          })

        val lr = new LinearRegression()
          .setMaxIter(100)

        val lrModel = lr.fit(trainingSet)

        val flightCode = arrival.IATA
        val day = SDate(arrival.Scheduled).getDayOfWeek()
        val month = SDate(arrival.Scheduled).getMonth()
        val year = SDate(arrival.Scheduled).getFullYear()

        val sparseFeatures = Seq[(Int, Double)](
          (features.indexOf(s"fd$flightCode-$day"), 1.0),
          (features.indexOf(s"fm$flightCode-$month"), 1.0),
          (features.indexOf(s"fy$flightCode-$year"), 1.0)
        )

        val labelAndFeatures = Seq((0d, Vectors.sparse(features.length, sparseFeatures)))

        import sparkSession.implicits._

        val arrivalFeatures = labelAndFeatures.toDF("label", "features")
        val predictedValue = lrModel
          .transform(arrivalFeatures)
          .select(col("prediction"))
          .rdd
          .map(_.getAs[Double](0))
          .collect()
          .head

        println(s"predicted value: $predictedValue")

        predictedValue
      })

      val expected = "yeah"

      stats === expected
    }
  }

  def parseJsonAndWrite(archetypes: List[(String, String)], outputFile: BufferedWriter, content: String): Unit = {
    VoyageManifestParser.parseVoyagePassengerInfo(content) match {
      case Success(vm) =>
        val splitsFromManifest = SplitsCalculator
          .convertVoyageManifestIntoPaxTypeAndQueueCounts(vm.ArrivalPortCode, vm)
          .map(p => p.copy(nationalities = None))

        val scheduledDateString = s"${vm.ScheduledDateOfArrival}T${vm.ScheduledTimeOfArrival}"
        Try {
          MilliDate(SDate(scheduledDateString).millisSinceEpoch)
        }
        match {
          case Failure(_) => println(s"Couldn't parse scheduled date string: $scheduledDateString")
          case Success(scheduled) =>
            val year = SDate(scheduled).getFullYear()
            val month = SDate(scheduled).getMonth()
            val dayOfWeek = SDate(scheduled).getDayOfWeek()

            val actuals = archetypes.map {
              case Tuple2(paxType, queueName) =>
                splitsFromManifest.find {
                  case ApiPaxTypeAndQueueCount(pt, qn, _, _) => pt.cleanName == paxType && qn == queueName
                }.map(_.paxCount).getOrElse(0d)
            }
            val totalPax = actuals.sum
            val percentages = actuals.map(_ / totalPax)

            val row = s"${vm.flightCode},${SDate(scheduled).toISOString()},${vm.DeparturePortCode},${vm.ArrivalPortCode},$year,$month,$dayOfWeek,${percentages.mkString(",")}\n"
            outputFile.write(row)
        }

      case _ =>
    }
  }

}
