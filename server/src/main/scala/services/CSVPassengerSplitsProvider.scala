package services

import com.typesafe.config.ConfigFactory
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared._

case class CSVPassengerSplitsProvider(flightPassengerSplitLines: Seq[String]) extends PassengerSplitRatioProvider {
  private val log = LoggerFactory.getLogger(getClass)

  log.info("Using CSV Splits")

  lazy val flightPaxSplits: Seq[CsvPassengerSplitsReader.FlightPaxSplit] = CsvPassengerSplitsReader.flightPaxSplitsFromLines(flightPassengerSplitLines)

  def splitRatioProvider: (ApiFlight => Option[SplitRatios]) = flight => {
    val flightDate = DateTime.parse(flight.SchDT)
    flightDate.monthOfYear.getAsText

    val foundFlights = flightPaxSplits.filter(row =>
      row.flightCode == flight.IATA &&
      row.dayOfWeek == flightDate.dayOfWeek.getAsText &&
      row.month == flightDate.monthOfYear.getAsText
    ).toList

    val splits: Option[SplitRatios] = foundFlights match {
      case head :: Nil =>
        log.info(s"Found split for $flight")
        Option(SplitRatios(CsvPassengerSplitsReader.splitRatioFromFlightPaxSplit(head)))
      case _ =>
        log.info(s"Failed to find split for $flight in CSV")
        None
    }
    splits
  }
}

object CsvPassengerSplitsReader {
  def calcQueueRatio(categoryPercentage: Int, queuePercentage: Int) = (categoryPercentage.toDouble / 100.0) * (queuePercentage.toDouble / 100.0)

  def splitRatioFromFlightPaxSplit(row: FlightPaxSplit): List[SplitRatio] = {
    List(
      SplitRatio(
        PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk),
        calcQueueRatio(row.eeaMachineReadable, row.eeaMachineReadableToDesk)),
      SplitRatio(
        PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eGate),
        calcQueueRatio(row.eeaMachineReadable, row.eeaMachineReadableToEgate)),
      SplitRatio(
        PaxTypeAndQueue(PaxTypes.eeaNonMachineReadable, Queues.eeaDesk),
        calcQueueRatio(row.eeaNonMachineReadable, row.eeaNonMachineReadableToDesk)),
      SplitRatio(
        PaxTypeAndQueue(PaxTypes.visaNational, Queues.nonEeaDesk),
        calcQueueRatio(row.visaNationals, row.visaToNonEEA)),
      SplitRatio(
        PaxTypeAndQueue(PaxTypes.nonVisaNational, Queues.nonEeaDesk),
        calcQueueRatio(row.nonVisaNationals, row.nonVisaToNonEEA))
    )
  }

  def flightPaxSplitsLinesFromConfig = {
    val splitsFileUrl = ConfigFactory.load.getString("passenger_splits_csv_url")
    scala.io.Source.fromURL(splitsFileUrl).getLines().drop(1).toSeq
  }

  case class FlightPaxSplit(
                          flightCode: String,
                          originPort: String,
                          eeaMachineReadable: Int,
                          eeaNonMachineReadable: Int,
                          nonVisaNationals: Int,
                          visaNationals: Int,
                          eeaMachineReadableToEgate: Int,
                          eeaMachineReadableToDesk: Int,
                          eeaNonMachineReadableToDesk: Int,
                          nonVisaToFastTrack: Int,
                          nonVisaToNonEEA: Int,
                          visaToFastTrack: Int,
                          visaToNonEEA: Int,
                          transfers: Int,
                          dayOfWeek: String,
                          month: String,
                          port: String,
                          terminal: String,
                          originCountryCode: String
                        )

  def flightPaxSplitsFromLines(flightPaxSplits: Seq[String]): Seq[FlightPaxSplit] = {

    flightPaxSplits.map { l =>
      val splitRow: Array[String] = l.split(",", -1)
      FlightPaxSplit(
        splitRow(0),
        splitRow(1),
        splitRow(2).toInt,
        splitRow(3).toInt,
        splitRow(4).toInt,
        splitRow(5).toInt,
        splitRow(6).toInt,
        splitRow(7).toInt,
        splitRow(8).toInt,
        splitRow(9).toInt,
        splitRow(10).toInt,
        splitRow(11).toInt,
        splitRow(12).toInt,
        splitRow(13).toInt,
        splitRow(14),
        splitRow(15),
        splitRow(16),
        splitRow(17),
        splitRow(18)
      )
    }
  }
}
