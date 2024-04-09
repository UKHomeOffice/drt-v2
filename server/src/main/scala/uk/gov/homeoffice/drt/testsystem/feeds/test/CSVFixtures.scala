package uk.gov.homeoffice.drt.testsystem.feeds.test

import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.{FlightCode, LiveArrival}
import uk.gov.homeoffice.drt.ports.Terminals
import uk.gov.homeoffice.drt.time.SDate

import scala.util.Try

object CSVFixtures {

  val log: Logger = LoggerFactory.getLogger(getClass)

  private object ArrivalsCSVFixture {
    object fieldMap {
      val Estimated = 4
      val Actual = 5
      val EstimatedChox = 6
      val ActualChox = 7
      val Stand = 8
      val MaxPax = 9
      val ActPax = 10
      val TranPax = 11
      val Terminal = 0
      val rawIATA = 1
      val Origin = 2
      val Scheduled = 3
    }
  }

  def csvPathToArrivalsOnDate(forDate: String, path: String): Seq[Try[LiveArrival]] = {

    def timeToSDate: String => Option[MillisSinceEpoch] = timeToSDateOnDate(forDate)

    val maybeArrivals: Seq[Try[LiveArrival]] = csvPathToRows(path).drop(1).map(csvRow => {
      val fields = csvRow.split(",")
      import ArrivalsCSVFixture.fieldMap._
      val (carrierCode, voyageNumber, suffix) = FlightCode.flightCodeToParts(fields(rawIATA))
      Try(LiveArrival(
        operator = None,
        maxPax = Option(fields(MaxPax).toInt),
        totalPax = Option(fields(ActPax).toInt),
        transPax = Option(fields(TranPax).toInt),
        terminal = Terminals.Terminal(fields(Terminal)),
        voyageNumber = voyageNumber.numeric,
        carrierCode = carrierCode.code,
        flightCodeSuffix = suffix.map(_.suffix),
        origin = fields(Origin),
        scheduled = timeToSDate(fields(Scheduled)).getOrElse(SDate.now().millisSinceEpoch),
        estimated = timeToSDate(fields(Estimated)),
        touchdown = timeToSDate(fields(Actual)),
        estimatedChox = timeToSDate(fields(EstimatedChox)),
        actualChox = timeToSDate(fields(ActualChox)),
        status = "Unk",
        gate = None,
        stand = Option(fields(Stand)),
        runway = None,
        baggageReclaim = None,
      ))
    })

    log.info(s"Found ${maybeArrivals.length} arrival fixtures in $path")

    maybeArrivals
  }

  private def timeToSDateOnDate(forDate: String)(time: String): Option[MillisSinceEpoch] = SDate.tryParseString(forDate + "T" + time + "Z")
    .toOption
    .map(_.millisSinceEpoch)

  private def csvPathToRows(fileName: String): Seq[String] = {
    val bufferedSource = scala.io.Source.fromFile(fileName)
    val lines = bufferedSource.getLines()
    lines.toList
  }
}
