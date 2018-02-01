package services.advpaxinfo

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.file.Paths

import com.typesafe.config.ConfigFactory
import drt.shared._
import org.specs2.mutable.Specification
import passengersplits.core.SplitsCalculator
import services.{Manifests, SDate}

import scala.collection.immutable


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
//      skipped("These were used to help write the exporting code for real API files which are not normally accessible")
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
  }

}