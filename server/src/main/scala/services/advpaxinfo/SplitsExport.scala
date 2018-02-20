package services.advpaxinfo

import java.io.{BufferedWriter, File, FileInputStream, FileWriter}
import java.util.zip.ZipInputStream

import com.typesafe.config.ConfigFactory
import drt.shared.{ApiPaxTypeAndQueueCount, DqEventCodes, MilliDate, PaxType}
import passengersplits.core.ZipUtils._
import passengersplits.core.{SplitsCalculator, ZipUtils}
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import services.SDate


case class FlightSummary(flightCode: String, arrivalDate: String, arrivalTime: String, arrivalPort: String, isInteractive: Option[Boolean], nationalities: Map[String, Int])

case class HistoricSplitsCollection(flightCode: String, originPort: String, arrivalPort: String, scheduled: MilliDate, splits: Seq[ApiPaxTypeAndQueueCount])


object SplitsExport {

  import play.api.libs.json._

  def getListOfFiles(dir: String): List[File] = {
    val d = new File(dir)
    if (d.exists && d.isDirectory) {
      val files = d.listFiles.filter(file => file.isFile && file.getName.takeRight(4) == ".zip").toList
      println(s"Found ${files.length} files")
      files
    } else {
      List[File]()
    }
  }

  def fileNameFilter(carrierList: List[String]): String => Boolean = (fileName: String) => {
    val fileNameParts = fileName.split("_")
    val eventCode = fileNameParts(4)
    val carrierCode = fileNameParts(3).take(2)
    val isDc = eventCode == DqEventCodes.DepartureConfirmed
    val isInterestingCarrier = carrierList.contains(carrierCode)

    isDc && isInterestingCarrier
  }

  def applyToUnzippedFileContent[X](files: List[File], carriers: List[String], apply: (UnzippedFileContent) => X): List[X] = {
    files
      .sortBy(_.getAbsolutePath)
      .flatMap(file => {
        val zipStream = new ZipInputStream(new FileInputStream(file.getAbsolutePath))
        val allUnzipped: Seq[UnzippedFileContent] = unzipAllFilesInStream(zipStream, fileNameFilter(carriers))

        zipStream.close()

        allUnzipped.map(apply)
      })
  }

  def extractFileContentFromZips(files: List[File], carriers: List[String]): List[String] = applyToUnzippedFileContent(files, carriers, _.content)

  def extractSummariesFromZips(files: List[File], carriers: List[String]): (List[String], List[FlightSummary]) = {
    val relevantSummaries = applyToUnzippedFileContent(files, carriers, ufc => summaryFromJson(ufc.content))
      .collect { case Some(fs) => fs }

    SplitsExport.expandToFullNationalities(relevantSummaries)
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
    val rawZipFilesPath: String = ConfigFactory.load.getString("dq.raw_zip_files_path")

    val files = SplitsExport.getListOfFiles(rawZipFilesPath)

    SplitsExport.extractSummariesFromZips(files, carriers)
  }

  def summaryFromJson(jsonString: String): Option[FlightSummary] = {
    val json = Json.parse(jsonString)
    val flightNumber = (json \ "VoyageNumber").as[String]
    val carrierCode = (json \ "CarrierCode").as[String]

    val flightCode = s"$carrierCode$flightNumber"
    val arrivalDate = (json \ "ScheduledDateOfArrival").as[String]
    val arrivalTime = (json \ "ScheduledTimeOfArrival").as[String]
    val portCode = (json \ "ArrivalPortCode").as[String]

    val pax = (json \ "PassengerList").get.asInstanceOf[JsArray].value

    val interactiveFlagExists = pax.exists(p => (p \ "PassengerIdentifier").asOpt[String].isDefined)

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

    Option(FlightSummary(flightCode, arrivalDate, arrivalTime, portCode, isInteractive, countryCounts))

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

    bw.close()
  }

  def historicSplitsCollection(portCode: String, manifests: List[VoyageManifest]): List[HistoricSplitsCollection] = {
    val historicSplits: List[HistoricSplitsCollection] = manifests
      .collect {
        case vm if vm.EventCode == DqEventCodes.DepartureConfirmed =>
          val splitsFromManifest = SplitsCalculator
            .convertVoyageManifestIntoPaxTypeAndQueueCounts(portCode, vm)
            .map(p => p.copy(nationalities = None))
          val scheduled = MilliDate(SDate(s"${vm.ScheduledDateOfArrival}T${vm.ScheduledTimeOfArrival}").millisSinceEpoch)

          HistoricSplitsCollection(vm.flightCode, vm.DeparturePortCode, vm.ArrivalPortCode, scheduled, splitsFromManifest)
      }
    historicSplits
  }

  def averageFlightSplitsByMonthAndDay(historicSplits: List[HistoricSplitsCollection], archetypes: List[(PaxType, String)]): Map[(String, Int, Int, Int), List[Double]] = {
    historicSplits
      .groupBy(_.flightCode)
      .flatMap {
        case (flightCode, splitsCollections) =>
          splitsCollections
            .groupBy(splitsCollection => {
              val scheduled = SDate(splitsCollection.scheduled)
              val yearMonthDay = Tuple3(scheduled.getFullYear(), scheduled.getMonth(), scheduled.getDayOfWeek())
              yearMonthDay
            })
            .map {
              case ((year, month, day), splitsForYearMonthAndDay) =>
                val paxCounts = splitsForYearMonthAndDay.length match {
                  case 0 => List[Double]()
                  case _ => archetypes
                    .map {
                      case (paxType, queueName) =>
                        val monthDayArchetypeCounts: List[Double] = splitsForYearMonthAndDay
                          .flatMap(splits =>
                            splits.splits.filter(ptqc =>
                              ptqc.passengerType == paxType && ptqc.queueType == queueName))
                          .map(_.paxCount)

                        val average = (monthDayArchetypeCounts.sum, monthDayArchetypeCounts.length) match {
                          case (_, 0) => 0.0
                          case (archetypeSum, numFlights) => archetypeSum / numFlights
                        }

                        average
                    }
                }
                ((flightCode, year, month, day), paxCounts)
            }
            .toList
      }
  }
}
