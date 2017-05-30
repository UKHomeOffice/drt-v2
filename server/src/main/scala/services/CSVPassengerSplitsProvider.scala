package services

import com.typesafe.config.ConfigFactory
import drt.shared.PassengerSplits.{SplitsPaxTypeAndQueueCount, VoyagePaxSplits}
import drt.shared.PaxTypes.EeaMachineReadable
import drt.shared.Queues.{EGate, EeaDesk}
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared._

object CSVPassengerSplitsProvider {
  def egatePercentageFromSplit(split: Option[SplitRatios], defaultPct: Double) = {
    split
      .map(_.splits
        .find(p => p.paxType.queueType == Queues.EGate)
        .map(_.ratio).getOrElse(defaultPct)).getOrElse(defaultPct)
  }

  def applyEgatesSplits(ptaqc: List[SplitsPaxTypeAndQueueCount], egatePct: Double): List[SplitsPaxTypeAndQueueCount] = {
    ptaqc.flatMap {
      case s@SplitsPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, count) =>
        val eeaDeskPax = Math.round(count * (1 - egatePct)).toInt
        s.copy(queueType = EGate, paxCount = count - eeaDeskPax) ::
          s.copy(queueType = EeaDesk, paxCount = eeaDeskPax) :: Nil
      case s => s :: Nil
    }
  }

  def applyEgates(vps: VoyagePaxSplits, egatePct: Double): VoyagePaxSplits = vps.copy(paxSplits = applyEgatesSplits(vps.paxSplits, egatePct))

}

case class CSVPassengerSplitsProvider(flightPassengerSplitLines: Seq[String]) extends PassengerSplitRatioProvider {
  private val log = LoggerFactory.getLogger(getClass)

  log.info("Initialising CSV Splits")
  val SplitOrigin = "CSV"
  lazy val flightPaxSplits: Seq[CsvPassengerSplitsReader.FlightPaxSplit] = {
    val splitsLines = CsvPassengerSplitsReader.flightPaxSplitsFromLines(flightPassengerSplitLines)
    log.info(s"Initialising CSV Splits will use $splitsLines")
    splitsLines
  }

  def splitRatioProvider: (ApiFlight => Option[SplitRatios]) = flight => {
    val flightDate = DateTime.parse(flight.SchDT)
    //todo - we should profile this, but it's likely much more efficient to store in nested map IATA -> DayOfWeek -> MonthOfYear
    //todo OR IATA -> (DayOfWeek, MonthOfYear)
    val iata = flight.IATA
    val dayOfWeek = flightDate.dayOfWeek.getAsText
    val month = flightDate.monthOfYear.getAsText

    val splitOpt = getFlightSplitRatios(iata, dayOfWeek, month)
    splitOpt match {
      case Some(sr) => log.info(s"Found SplitRatio for $flight as $sr")
      case None => log.info(s"Failed to find split for $flight")
    }
    splitOpt
  }

  def getFlightSplitRatios(iata: String, dayOfWeek: String, month: String): Option[SplitRatios] = {
    flightPaxSplits.find(row => {
      row.flightCode == iata &&
        row.dayOfWeek == dayOfWeek &&
        row.month == month
    }
    ).map(matchFlight => SplitRatios(CsvPassengerSplitsReader.splitRatioFromFlightPaxSplit(matchFlight), origin = SplitOrigin))
  }


}

object CsvPassengerSplitsReader {
  def calcQueueRatio(categoryPercentage: Int, queuePercentage: Int) = (categoryPercentage.toDouble / 100.0) * (queuePercentage.toDouble / 100.0)

  def splitRatioFromFlightPaxSplit(row: FlightPaxSplit): List[SplitRatio] = {
    List(
      SplitRatio(
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk),
        calcQueueRatio(row.eeaMachineReadable, row.eeaMachineReadableToDesk)),
      SplitRatio(
        PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EGate),
        calcQueueRatio(row.eeaMachineReadable, row.eeaMachineReadableToEgate)),
      SplitRatio(
        PaxTypeAndQueue(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk),
        calcQueueRatio(row.eeaNonMachineReadable, row.eeaNonMachineReadableToDesk)),
      SplitRatio(
        PaxTypeAndQueue(PaxTypes.VisaNational, Queues.NonEeaDesk),
        calcQueueRatio(row.visaNationals, row.visaToNonEEA)),
      SplitRatio(
        PaxTypeAndQueue(PaxTypes.NonVisaNational, Queues.NonEeaDesk),
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
