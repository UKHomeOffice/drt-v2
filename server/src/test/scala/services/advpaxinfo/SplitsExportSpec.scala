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
import drt.shared._
import org.slf4j.LoggerFactory
import org.specs2.mutable.Specification
import passengersplits.core.SplitsCalculator
import passengersplits.parsing.VoyageManifestParser
import services.SDate

import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success, Try}


class SplitsExportSpec extends Specification {
  lazy val rawZipFilesPath: String = "/atmos-backup"
  val log = LoggerFactory.getLogger(getClass)

  val archetypes = List(
    Tuple2(PaxTypes.EeaMachineReadable.cleanName, Queues.EeaDesk),
    Tuple2(PaxTypes.EeaNonMachineReadable.cleanName, Queues.EeaDesk),
    Tuple2(PaxTypes.VisaNational.cleanName, Queues.NonEeaDesk),
    Tuple2(PaxTypes.NonVisaNational.cleanName, Queues.NonEeaDesk)
  )

  "Looking at raw API data" >> {

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

    "I can get export splits from API directly to csv" >> {
      skipped("no need to export the csv every time")
      import akka.stream.scaladsl._

      val files = SplitsExport
        .getListOfFiles(rawZipFilesPath)
        .filterNot(f => {
          val fileNameParts = f.getName.split("_")
          val date = Integer.parseInt(fileNameParts(2))
          val tooOld = date < 161201
          if (tooOld) println(s"ignoring old zip ${f.getName} - $date < 161201")
          tooOld
        })

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
  }

  implicit val actorSystem: ActorSystem = ActorSystem("AdvPaxInfo", ConfigFactory.empty())
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
