package services.advpaxinfo

import java.io._
import java.util.zip.ZipInputStream

import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification
import passengersplits.core.ZipUtils


case class FlightSummary(flightCode: String, arrivalDate: String, arrivalTime: String, arrivalPort: String, isInteractive: Option[Boolean], nationalities: Map[String, Int])


class SplitsExportSpec extends Specification {
  val rawZipFilesPath: String = ConfigFactory.load.getString("dq.raw_zip_files_path")

  "I can list the files in the atmos-backup dir" >> {
    val files = SplitsExport.getListOfFiles(rawZipFilesPath)

    files.length !== 0
  }

  "I can unzip the zips" >> {
    val files = SplitsExport.getListOfFiles(rawZipFilesPath)

    val content: List[String] = SplitsExport.extractFilesFromZips(files.take(1))

    content.head.length !== 0
  }

  "I can parse fields from the json" >> {
    val files = SplitsExport.getListOfFiles(rawZipFilesPath)

    val carriers = List(
      "AB", "NY", "AP", "IZ", "5O", "AG", "5Y", "S4", "8H", "0B", "OK", "J7", "WK", "OF", "5F", "FH", "FH",
      "4U", "5K", "6H", "B0", "VL", "IG", "NL", "SX", "RE", "ZT", "VG", "W6", "8Z")

    val filesToInclude = files.sortBy(_.getAbsolutePath).splitAt(21500)._2
    println(s"first date: ${filesToInclude.head}")
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

    println(result)

    result === expected
  }

  object SplitsExport {

    import play.api.libs.json._

    def getListOfFiles(dir: String): List[File] = {
      val d = new File(dir)
      if (d.exists && d.isDirectory) {
        d.listFiles.filter(_.isFile).toList
      } else {
        List[File]()
      }
    }

    def extractFilesFromZips(files: List[File]): List[String] = {
      val content = files.map(file => {
        val zipStream = new ZipInputStream(new FileInputStream(file.getAbsolutePath))
        val allUnzipped: Seq[ZipUtils.UnzippedFileContent] = ZipUtils.unzipAllFilesInStream(zipStream)

        allUnzipped.map(unzippedFile => unzippedFile.content)
      })
      content.flatten
    }

    def extractSummariesFromZips(files: List[File], carriers: List[String]): (List[String], List[FlightSummary]) = {
      val relevantSummaries: List[FlightSummary] = files
        .sortBy(_.getAbsolutePath)
        .flatMap(file => {
          println(s"Processing $file")
          val zipStream = new ZipInputStream(new FileInputStream(file.getAbsolutePath))
          val allUnzipped: Seq[ZipUtils.UnzippedFileContent] = ZipUtils.unzipAllFilesInStream(zipStream)

          allUnzipped.map(unzippedFile => summaryFromJson(unzippedFile.content, carriers))
        })
        .collect { case Some(fs) => fs }

      SplitsExport.expandToFullNationalities(relevantSummaries)
    }

    def getFlightSummaries(jsons: List[String], carriersFilter: List[String]): List[FlightSummary] = jsons
      .map(json => summaryFromJson(json, carriersFilter))
      .collect {
        case Some(summary) => summary
      }

    def expandToFullNationalities(summaries: List[FlightSummary]): (List[String], List[FlightSummary]) = {
      val allCountries = summaries
        .foldLeft(List[String]()) {
          case (soFar, FlightSummary(_, _, _, _, _, countries)) =>
            countries.keys.foldLeft(soFar) {
              case (countriesSoFarForFlight, flightCountry) if countriesSoFarForFlight.contains(flightCountry) => countriesSoFarForFlight
              case (countriesSoFarForFlight, flightCountry) => flightCountry :: countriesSoFarForFlight
            }
        }
        .sortBy(identity)
      val summariesWithFullNats = summaries.map(s => {
        val flightFullCountries = allCountries.map(c => {
          (c, s.nationalities.getOrElse(c, 0))
        })
        s.copy(nationalities = flightFullCountries.toMap)
      })

      (allCountries, summariesWithFullNats)
    }

    def fullSummariesForCarriers(carriers: List[String]): Unit = {

      val files = SplitsExport.getListOfFiles(rawZipFilesPath)

      SplitsExport.extractSummariesFromZips(files, carriers)
    }

    def summaryFromJson(jsonString: String, carriersFilter: List[String]): Option[FlightSummary] = {
      val json = Json.parse(jsonString)
      val flightNumber = (json \ "VoyageNumber").as[String]
      val carrierCode = (json \ "CarrierCode").as[String]
      val eventCode = (json \ "EventCode").as[String]

      carriersFilter.find(validCarrier => {
        validCarrier == carrierCode.toString && eventCode == "DC"
      }).map(_ => {
        val flightCode = s"$carrierCode$flightNumber"
        val arrivalDate = (json \ "ScheduledDateOfArrival").as[String]
        val arrivalTime = (json \ "ScheduledTimeOfArrival").as[String]
        val portCode = (json \ "ArrivalPortCode").as[String]

        val pax = (json \ "PassengerList").get.asInstanceOf[JsArray].value

        val interactiveFlagExists = pax.exists(p => (p \"PassengerIdentifier").asOpt[String].isDefined)

        val isInteractive = if (interactiveFlagExists)
          Option(pax.exists(p => (p \ "PassengerIdentifier").as[String].nonEmpty))
        else
          None

        val countryCounts: Map[String, Int] = pax
          .map(p => {
            val natCode = p \ "NationalityCountryCode"
            val natCodeStr = natCode.as[String] match {
              case "" => "n/a"
              case cc => cc
            }
            val pid = if (interactiveFlagExists)
              (p \ "PassengerIdentifier").as[String]
            else ""
            (natCodeStr, pid)
          })
          .collect {
            case (natCode, pid) if isInteractive.isEmpty || !isInteractive.get || pid.isEmpty => natCode
          }
          .foldLeft(Map[String, Int]()) {
            case (soFar, country) => soFar.updated(country, soFar.getOrElse(country, 0) + 1)
          }

        FlightSummary(flightCode, arrivalDate, arrivalTime, portCode, isInteractive, countryCounts)
      })
    }

    def writeCsvReport(nations: List[String], summaries: List[FlightSummary], filePath: String): Unit = {
      val headers = "Flight code,Arrival date,Arrival time,Arrival port,Non-iAPI," + nations.mkString(",")
      val csvContent =
        headers +
          "\n" +
          summaries
            .map(s => {
              val nationalities = nations.map(n => s.nationalities.getOrElse(n, 0)).mkString(",")
              val nonInteractive = s.isInteractive match {
                case None => "?"
                case Some(false) => "Y"
                case _ => ""
              }

              s"${s.flightCode},${s.arrivalDate},${s.arrivalTime},${s.arrivalPort},$nonInteractive,$nationalities"
            })
            .mkString("\n")

      val file = new File(filePath)
      val bw = new BufferedWriter(new FileWriter(file))
      bw.write(csvContent)
    }
  }

}