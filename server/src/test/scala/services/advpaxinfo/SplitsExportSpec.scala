package services.advpaxinfo

import java.io.File
import java.nio.file.{Path, Paths}

import akka.stream.IOResult
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification
import passengersplits.core.SplitsCalculator
import passengersplits.parsing.VoyageManifestParser
import services.Manifests

import scala.collection.immutable
import scala.concurrent.Future
import scala.io.BufferedSource


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

      val content: List[String] = SplitsExport.extractFilesFromZips(files.take(1))

      content.head.length !== 0
    }

    "I can parse fields from the json" >> {
      skipped("These were used to help write the exporting code for real API files which are not normally accessible")
      val files = SplitsExport.getListOfFiles(rawZipFilesPath)

      val carriers = List(
        "AB", "NY", "AP", "IZ", "5O", "AG", "5Y", "S4", "8H", "0B", "OK", "J7", "WK", "OF", "5F", "FH", "FH",
        "4U", "5K", "6H", "B0", "VL", "IG", "NL", "SX", "RE", "ZT", "VG", "W6", "8Z")

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

    "I can get some archetypes from a zip" >> {
      import akka.stream.scaladsl._

      val files = SplitsExport.getListOfFiles(rawZipFilesPath)

      val manifests = files.take(1).flatMap(file => {
        val x = FileIO.fromPath(Paths.get(file.getAbsolutePath))
        Manifests
          .fileNameAndContentFromZip(file.getName, x, None)
          .map {
            case (_, vm) => vm
          }
      })

      manifests
        .map(vm => {
          val splitsFromManifest = SplitsCalculator.convertVoyageManifestIntoPaxTypeAndQueueCounts("STN", vm)
          println(s"splitsFromManifest: $splitsFromManifest")
        })

      1 === 1
    }
  }
}